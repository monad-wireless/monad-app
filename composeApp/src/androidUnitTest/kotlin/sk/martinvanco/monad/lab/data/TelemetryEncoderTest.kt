package sk.martinvanco.monad.lab.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the handset actually puts on the wire.
 *
 * Three rules are defended here, and every one of them is a way this could be silently wrong rather
 * than loudly broken:
 *
 *   1. **Samples keep their own timestamps.** This is the entire reason the app speaks OTLP itself
 *      instead of going through a relay. If the encoder ever stamped "now", a handset that lost the
 *      network for five minutes would report a flat line and a step, and the flat line would read as
 *      five healthy minutes.
 *   2. **`session_id` never becomes a metric label.** It is new every walk, so as a label it mints a
 *      time series per session against a 365-day retention.
 *   3. **`state` never becomes a metric label either.** A gauge whose label set changes strands the
 *      old series holding its last value, so a stream that died would keep publishing its healthy
 *      rate under the previous state's series.
 */
class TelemetryEncoderTest {

    private fun stream(
        name: String = "pose",
        state: String = "degraded",
        rateHz: Double = 3.1,
        expected: Double? = 10.0,
        delivered: Double? = 0.31,
    ) = TelemetryStream(
        stream = name,
        state = state,
        worstState = state,
        rateHz = rateHz,
        expectedRateHz = expected,
        totalEvents = 707,
        silenceMs = 0,
        deliveredFraction = delivered,
        troubleMs = 12_000,
    )

    private fun sample(
        wallMs: Long = 1_787_168_907_000,
        monoNs: Long = 918_273_645_000,
        overall: String = "degraded",
        streams: List<TelemetryStream> = listOf(stream()),
    ) = TelemetrySample(
        monoNs = monoNs,
        wallMs = wallMs,
        elapsedMs = 42_000,
        phase = "RUNNING",
        overall = overall,
        recording = true,
        clockGate = "ok",
        streams = streams,
    )

    private fun batch(
        samples: List<TelemetrySample> = listOf(sample()),
        dropped: Int = 0,
    ) = TelemetryBatch(
        sessionId = "session-1",
        participantToken = "2",
        site = "fiit-library-silent",
        platform = "ios",
        appVersion = "1.4.0+41.g9a1d2f2b",
        dropped = dropped,
        samples = samples,
    )

    private fun OtlpMetricsRequest.metric(name: String): OtlpMetric? =
        resourceMetrics.single().scopeMetrics.single().metrics.firstOrNull { it.name == name }

    // ── 1. timestamps ────────────────────────────────────────────────────────────

    @Test
    fun `every data point carries the sample's own wall clock, in nanoseconds`() {
        val encoded = TelemetryEncoder.metrics(
            batch(listOf(sample(wallMs = 1_700_000_000_000), sample(wallMs = 1_700_000_001_000)))
        )

        val points = encoded.metric(TelemetryEncoder.RATE_HZ)!!.gauge.dataPoints
        assertContentEquals(
            listOf("1700000000000000000", "1700000001000000000"),
            points.map { it.timeUnixNano },
        )
    }

    @Test
    fun `a ten minute backlog encodes ten minutes of distinct timestamps`() {
        val samples = (0 until 600).map { sample(wallMs = 1_700_000_000_000 + it * 1_000L) }

        val points = TelemetryEncoder.metrics(batch(samples))
            .metric(TelemetryEncoder.SESSION_SEVERITY)!!.gauge.dataPoints

        assertEquals(600, points.size)
        assertEquals(600, points.map { it.timeUnixNano }.toSet().size)
    }

    // ── 2. and 3. label containment ──────────────────────────────────────────────

    @Test
    fun `no metric label carries the session id or the build id`() {
        val encoded = TelemetryEncoder.metrics(batch())

        val labels = encoded.resourceMetrics.single().scopeMetrics.single().metrics
            .flatMap { it.gauge.dataPoints }
            .flatMap { it.attributes }

        assertTrue(labels.none { it.key == "session_id" }, "session_id must never be a metric label")
        assertTrue(labels.none { it.value.stringValue == "session-1" })
        assertTrue(labels.none { it.key == "build_id" })
        assertTrue(labels.none { it.key == "phase" })
    }

    @Test
    fun `no metric label carries the stream state`() {
        val encoded = TelemetryEncoder.metrics(batch())

        val labels = encoded.resourceMetrics.single().scopeMetrics.single().metrics
            .flatMap { it.gauge.dataPoints }
            .flatMap { it.attributes }

        assertTrue(labels.none { it.key == "state" }, "state travels as a number, never as a label")
        assertTrue(labels.none { it.value.stringValue == "degraded" })
    }

    @Test
    fun `the metric label set is exactly site, platform, participant and stream`() {
        val encoded = TelemetryEncoder.metrics(batch())

        val perStream = encoded.metric(TelemetryEncoder.RATE_HZ)!!.gauge.dataPoints.single()
        assertEquals(
            setOf("site", "platform", "participant", "stream"),
            perStream.attributes.map { it.key }.toSet(),
        )

        val perSession = encoded.metric(TelemetryEncoder.SESSION_SEVERITY)!!.gauge.dataPoints.single()
        assertEquals(
            setOf("site", "platform", "participant"),
            perSession.attributes.map { it.key }.toSet(),
        )
    }

    @Test
    fun `participant is a label so nine concurrent handsets do not collide`() {
        val a = TelemetryEncoder.metrics(batch()).metric(TelemetryEncoder.SESSION_SEVERITY)!!
        val b = TelemetryEncoder.metrics(
            TelemetryBatch("s2", "7", "fiit-library-silent", "ios", "v", 0, listOf(sample()))
        ).metric(TelemetryEncoder.SESSION_SEVERITY)!!

        val labelOf = { m: OtlpMetric ->
            m.gauge.dataPoints.single().attributes.single { it.key == "participant" }.value.stringValue
        }
        assertEquals("2", labelOf(a))
        assertEquals("7", labelOf(b))
    }

    // ── severity encoding ────────────────────────────────────────────────────────

    @Test
    fun `state severities match the app's own ordering`() {
        val states = listOf("not_applicable", "alive", "idle", "degraded", "stale", "dead")
        val encoded = TelemetryEncoder.metrics(
            batch(listOf(sample(streams = states.mapIndexed { i, s -> stream(name = "s$i", state = s) })))
        )

        val severities = encoded.metric(TelemetryEncoder.SEVERITY)!!.gauge.dataPoints.map { it.asDouble }
        assertContentEquals(listOf(0.0, 0.0, 1.0, 2.0, 3.0, 4.0), severities)
    }

    @Test
    fun `recording is one while recording`() {
        val encoded = TelemetryEncoder.metrics(batch())
        assertEquals(
            1.0,
            encoded.metric(TelemetryEncoder.SESSION_RECORDING)!!.gauge.dataPoints.single().asDouble,
        )
    }

    // ── delivered fraction is only for paced streams ──────────────────────────────

    @Test
    fun `an event-driven stream reports no delivered fraction rather than zero`() {
        val encoded = TelemetryEncoder.metrics(
            batch(listOf(sample(streams = listOf(
                stream(name = "ground_truth", expected = null, delivered = null),
                stream(name = "pose", expected = 10.0, delivered = 0.31),
            ))))
        )

        val delivered = encoded.metric(TelemetryEncoder.DELIVERED)!!.gauge.dataPoints
        assertEquals(1, delivered.size, "only the paced stream reports a fraction")
        assertEquals(
            "pose",
            delivered.single().attributes.single { it.key == "stream" }.value.stringValue,
        )
    }

    @Test
    fun `a metric with no data points is omitted rather than sent empty`() {
        val encoded = TelemetryEncoder.metrics(
            batch(listOf(sample(streams = listOf(stream(expected = null, delivered = null)))))
        )

        assertNull(encoded.metric(TelemetryEncoder.DELIVERED))
    }

    @Test
    fun `every emitted metric name is on the collector's allow-list`() {
        val encoded = TelemetryEncoder.metrics(batch(listOf(sample(), sample()), dropped = 3))

        val names = encoded.resourceMetrics.single().scopeMetrics.single().metrics.map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue(
            TelemetryEncoder.METRIC_NAMES.containsAll(names),
            "emitted $names but the allow-list is ${TelemetryEncoder.METRIC_NAMES}",
        )
    }

    @Test
    fun `the drop count is stamped at the newest sample`() {
        val encoded = TelemetryEncoder.metrics(
            batch(listOf(sample(wallMs = 1_000), sample(wallMs = 2_000)), dropped = 137)
        )

        val point = encoded.metric(TelemetryEncoder.DROPPED)!!.gauge.dataPoints.single()
        assertEquals(137.0, point.asDouble)
        assertEquals(Otlp.nanosOf(2_000), point.timeUnixNano)
    }

    // ── resource identity ────────────────────────────────────────────────────────

    @Test
    fun `the resource names the service so it lands beside csid and api`() {
        val attributes = TelemetryEncoder.metrics(batch())
            .resourceMetrics.single().resource.attributes
            .associate { it.key to it.value.stringValue }

        assertEquals("monad-app", attributes["service.name"])
        assertEquals("1.4.0+41.g9a1d2f2b", attributes["service.version"])
    }

    // ── logs: exceptions only ────────────────────────────────────────────────────

    @Test
    fun `a healthy stretch produces no log records at all`() {
        val healthy = sample(overall = "alive", streams = listOf(stream(state = "alive")))
        assertNull(TelemetryEncoder.logs(batch(listOf(healthy, healthy))))
    }

    @Test
    fun `an idle stream is not worth a log line either`() {
        val idle = sample(overall = "idle", streams = listOf(stream(state = "idle")))
        assertNull(TelemetryEncoder.logs(batch(listOf(idle))))
    }

    @Test
    fun `only the unhealthy samples become log records`() {
        val healthy = sample(wallMs = 1_000, overall = "alive", streams = listOf(stream(state = "alive")))
        val bad = sample(wallMs = 2_000, overall = "dead", streams = listOf(stream(state = "dead")))

        val logs = TelemetryEncoder.logs(batch(listOf(healthy, bad, healthy)))

        assertNotNull(logs)
        val records = logs.resourceLogs.single().scopeLogs.single().logRecords
        assertEquals(1, records.size)
        assertEquals(Otlp.nanosOf(2_000), records.single().timeUnixNano)
    }

    @Test
    fun `a log record carries the session id, build id and monotonic stamp`() {
        val logs = TelemetryEncoder.logs(batch(listOf(sample(overall = "stale"))))!!
        val attributes = logs.resourceLogs.single().scopeLogs.single().logRecords.single()
            .attributes.associate { it.key to it.value.stringValue }

        assertEquals("session-1", attributes["session_id"])
        assertEquals("1.4.0+41.g9a1d2f2b", attributes["build_id"])
        assertEquals("918273645000", attributes["mono_ns"])
        assertEquals("RUNNING", attributes["phase"])
        assertEquals("ok", attributes["clock_gate"])
    }

    @Test
    fun `a log record names which streams were troubled`() {
        val logs = TelemetryEncoder.logs(batch(listOf(sample(
            overall = "degraded",
            streams = listOf(
                stream(name = "clock", state = "alive"),
                stream(name = "pose", state = "degraded"),
            ),
        ))))!!

        val record = logs.resourceLogs.single().scopeLogs.single().logRecords.single()
        assertEquals(
            "pose",
            record.attributes.single { it.key == "troubled_streams" }.value.stringValue,
        )
        assertTrue(record.body.stringValue.contains("pose=degraded"))
        assertEquals(Otlp.SEVERITY_WARN, record.severityNumber)
    }
}
