package sk.martinvanco.monad.lab.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of OTLP/HTTP JSON this app emits.
 *
 * WHY HAND-WRITTEN AND NOT AN SDK
 * -------------------------------
 * There is no OpenTelemetry SDK for Kotlin/Native, so `opentelemetry-java` would give telemetry on
 * Android and silence on the handsets that actually run the experiment. OTLP/JSON over HTTP is a
 * stable, documented part of the protocol and the collector accepts it natively, so the honest
 * portable choice is to encode it here — a few data classes, no platform code, one behaviour on both
 * targets.
 *
 * WHY THIS MATTERS MORE THAN IT LOOKS
 * ----------------------------------
 * Because the app writes `timeUnixNano` itself, every sample lands at the moment it was TAKEN rather
 * than the moment it was delivered. A handset that loses the network for five minutes and then
 * flushes still produces a correct five minutes of history, not a flat line and a step. No relay can
 * offer that: an intermediary re-recording gauges stamps them with its own clock.
 *
 * The limit is the metric store's out-of-order window, not this encoder. Mimir accepts samples
 * backdated within `out_of_order_time_window` (10 minutes on this deployment), which is why the
 * shipper's buffer is capped at ten minutes rather than an hour — a sample it could not place
 * correctly is worse than a sample it admits to dropping.
 *
 * ATTRIBUTE VALUES ARE STRINGS, ALWAYS
 * -----------------------------------
 * OTLP's `AnyValue` is a union and the JSON encoding names the arm (`stringValue`, `intValue`, …).
 * Every attribute here is a `stringValue` on purpose: these become Prometheus labels, which are
 * strings anyway, and a union with one arm cannot be encoded wrongly.
 */

@Serializable
internal data class OtlpAnyValue(
    @SerialName("stringValue") val stringValue: String,
)

@Serializable
internal data class OtlpAttribute(
    @SerialName("key") val key: String,
    @SerialName("value") val value: OtlpAnyValue,
)

@Serializable
internal data class OtlpResource(
    @SerialName("attributes") val attributes: List<OtlpAttribute>,
)

@Serializable
internal data class OtlpScope(
    @SerialName("name") val name: String,
)

/**
 * One gauge reading.
 *
 * `timeUnixNano` is a String because OTLP/JSON encodes 64-bit integers as strings — a JSON number
 * cannot carry a nanosecond epoch without losing precision to the double it would be parsed into,
 * and the loss is about a hundred nanoseconds at present dates. Sending it as a number is the
 * classic way to get a collector to reject a payload with a type error.
 */
@Serializable
internal data class OtlpNumberDataPoint(
    @SerialName("timeUnixNano") val timeUnixNano: String,
    @SerialName("asDouble") val asDouble: Double,
    @SerialName("attributes") val attributes: List<OtlpAttribute>,
)

@Serializable
internal data class OtlpGauge(
    @SerialName("dataPoints") val dataPoints: List<OtlpNumberDataPoint>,
)

@Serializable
internal data class OtlpMetric(
    @SerialName("name") val name: String,
    @SerialName("unit") val unit: String,
    @SerialName("gauge") val gauge: OtlpGauge,
)

@Serializable
internal data class OtlpScopeMetrics(
    @SerialName("scope") val scope: OtlpScope,
    @SerialName("metrics") val metrics: List<OtlpMetric>,
)

@Serializable
internal data class OtlpResourceMetrics(
    @SerialName("resource") val resource: OtlpResource,
    @SerialName("scopeMetrics") val scopeMetrics: List<OtlpScopeMetrics>,
)

/** The body of `POST /v1/metrics`. */
@Serializable
internal data class OtlpMetricsRequest(
    @SerialName("resourceMetrics") val resourceMetrics: List<OtlpResourceMetrics>,
)

/**
 * One log record.
 *
 * `severityNumber` follows OTLP's scale — 9 is INFO, 13 is WARN. Both are sent rather than only the
 * text, because Loki's level detection reads the number and an operator filters on it.
 */
@Serializable
internal data class OtlpLogRecord(
    @SerialName("timeUnixNano") val timeUnixNano: String,
    @SerialName("severityNumber") val severityNumber: Int,
    @SerialName("severityText") val severityText: String,
    @SerialName("body") val body: OtlpAnyValue,
    @SerialName("attributes") val attributes: List<OtlpAttribute>,
)

@Serializable
internal data class OtlpScopeLogs(
    @SerialName("scope") val scope: OtlpScope,
    @SerialName("logRecords") val logRecords: List<OtlpLogRecord>,
)

@Serializable
internal data class OtlpResourceLogs(
    @SerialName("resource") val resource: OtlpResource,
    @SerialName("scopeLogs") val scopeLogs: List<OtlpScopeLogs>,
)

/** The body of `POST /v1/logs`. */
@Serializable
internal data class OtlpLogsRequest(
    @SerialName("resourceLogs") val resourceLogs: List<OtlpResourceLogs>,
)

internal object Otlp {
    const val SCOPE = "monad.lab.live"
    const val SEVERITY_INFO = 9
    const val SEVERITY_WARN = 13

    fun attribute(key: String, value: String): OtlpAttribute =
        OtlpAttribute(key, OtlpAnyValue(value))

    /** OTLP wants nanoseconds; the handset's wall clock counts milliseconds. */
    fun nanosOf(wallMillis: Long): String = (wallMillis * 1_000_000L).toString()
}
