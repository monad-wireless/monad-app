package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `payload_json` column of a `block_start` / `block_stop` row in `markers.tsv`.
 *
 * **This is a data contract.** The analysis side joins on these names, so renaming one is a silent
 * corpus split rather than a compile error — which is exactly why they are pinned here, in snake
 * case, next to the type that produces them, and round-tripped by a test.
 *
 * Two fields also appear as first-class TSV columns and not only inside this JSON:
 * `block_id` is written into the marker's `step_id` column, and the phase is the marker's `kind`
 * (`block_start` / `block_stop`). A reader that only wants block boundaries therefore never has to
 * parse JSON at all.
 *
 * ### Precision
 *
 * `mono_ns` on the marker row is captured at the instant of the operator's tap, **before** any
 * network work — so the boundary itself never pays for a sync burst. The [clock] block records how
 * well that stamp can be mapped onto the fleet timeline at that moment, so a poorly-synced boundary
 * can be down-weighted or excluded by the analysis rather than silently trusted. Cycling ramps are
 * 30 s and are T3's primary event set, so these boundaries are held to gate **G4b (250 ms)**, not
 * G4a (6 s): a 6 s error is 20 % of a ramp and would systematically mislabel plateau windows as
 * ramp at exactly the transitions T3 exists to analyse.
 */
@Serializable
data class BlockMarkerPayload(
    @SerialName("schema") val schema: String = SCHEMA,
    @SerialName("block_id") val blockId: String,
    @SerialName("zone_id") val zoneId: String,
    val level: Int,
    @SerialName("sub_condition") val subCondition: String,
    @SerialName("block_kind") val blockKind: String,
    /** `start` | `stop`. */
    val phase: String,
    /**
     * The lab session this block belongs to — the operator's session, the same id the printed
     * check-in code names and the same id `ground_truth.tsv` carries.
     */
    @SerialName("lab_session_id") val labSessionId: String,
    /**
     * The local recording whose `markers.tsv` this row lives in. Equal to [labSessionId] on the
     * operator's handset (the console anchors the lab session to its own recording), and carried
     * separately anyway so the equality is a checkable fact rather than an assumption.
     */
    @SerialName("recording_session_id") val recordingSessionId: String,
    /** Block ordinal within the session, from 1. Orders the stream without trusting timestamps. */
    val sequence: Int,
    @SerialName("designed_seconds") val designedSeconds: Int? = null,
    @SerialName("budget_min_seconds") val budgetMinSeconds: Int? = null,
    @SerialName("budget_max_seconds") val budgetMaxSeconds: Int? = null,
    /** Live ground-truth count at the mark, or null when there was none. */
    val tally: Int? = null,
    /** `room_live` | `room_stale` | `device_only`; null when there was no tally at all. */
    @SerialName("tally_source") val tallySource: String? = null,
    /** Guard-rail tokens raised at this edge. See [BlockWarningKind]. */
    val warnings: List<String> = emptyList(),
    val clock: BlockClockPayload? = null,
    // ---- stop edges only ----
    @SerialName("duration_ms") val durationMillis: Long? = null,
    /** `operator` | `superseded` | `session_end`. */
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("within_budget") val withinBudget: Boolean? = null,
) {
    companion object {
        const val SCHEMA: String = "monad-app/block-marker/v1"

        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun of(
            mark: BlockMark,
            labSessionId: String,
            recordingSessionId: String,
        ): BlockMarkerPayload = BlockMarkerPayload(
            blockId = mark.blockId,
            zoneId = mark.zoneId,
            level = mark.level,
            subCondition = mark.subCondition.wire,
            blockKind = mark.kind.wire,
            phase = mark.phase.wire,
            labSessionId = labSessionId,
            recordingSessionId = recordingSessionId,
            sequence = mark.sequence,
            designedSeconds = mark.kind.designedSeconds,
            budgetMinSeconds = mark.kind.minSeconds,
            budgetMaxSeconds = mark.kind.maxSeconds,
            tally = mark.tally?.count,
            tallySource = mark.tally?.source?.wire,
            warnings = mark.warnings.map { it.kind.wire },
            clock = mark.clock?.let { BlockClockPayload.of(it) },
            durationMillis = mark.durationMillis,
            stopReason = mark.stopReason?.wire,
            withinBudget = mark.durationMillis?.let { mark.kind.isWithinBudget(it) },
        )

        fun encode(payload: BlockMarkerPayload): String =
            json.encodeToString(serializer(), payload)

        fun decode(raw: String): BlockMarkerPayload? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}

/**
 * How well this boundary can be placed on the fleet timeline, as of the instant it was marked.
 *
 * Recorded per marker rather than only once per session because sync quality is not constant: a
 * phone that drifts mid-session produces boundaries of two different qualities, and an analysis
 * that had only a session-level number would have to treat them alike.
 */
@Serializable
data class BlockClockPayload(
    /** `not_run` | `not_applicable` | `no_samples` | `offset_only` | `ok`. */
    val status: String,
    val samples: Int,
    @SerialName("offset_ns") val offsetNanos: Long,
    @SerialName("rtt_ms") val rttMillis: Double,
    @SerialName("skew_ppm") val skewPpm: Double,
    /**
     * Max absolute residual of this phone's own sync samples against their own filtered fit.
     *
     * A proxy, and named so it cannot be mistaken for the registered estimand: the registered G4
     * residual is marker-vs-CSI and the CSI side lives on the fleet. A large value here **proves**
     * G4 will fail; a small one does not prove it will pass.
     */
    @SerialName("fit_residual_ms") val fitResidualMillis: Double? = null,
    /** Milliseconds since the newest sync sample. A fresh boundary sync keeps this small. */
    @SerialName("sync_age_ms") val syncAgeMillis: Long,
    @SerialName("meets_g4a") val meetsG4a: Boolean,
    @SerialName("meets_g4b") val meetsG4b: Boolean,
    /** This marker's `mono_ns` projected onto the collector timeline with the best fit available. */
    @SerialName("est_ref_ns") val estimatedReferenceNanos: Long? = null,
    /** `affine_fit` | `offset_only` | `none` — which projection produced [estimatedReferenceNanos]. */
    @SerialName("est_ref_source") val estimateSource: String,
    /** Sync samples the low-delay filter discarded before fitting. */
    @SerialName("samples_rejected") val samplesRejected: Int = 0,
) {
    companion object {
        fun of(stamp: ClockStamp): BlockClockPayload = BlockClockPayload(
            status = stamp.status.wire,
            samples = stamp.samples,
            offsetNanos = stamp.offsetNanos,
            rttMillis = stamp.rttMillis,
            skewPpm = stamp.skewPpm,
            fitResidualMillis = stamp.fitResidualMillis,
            syncAgeMillis = stamp.syncAgeMillis,
            meetsG4a = stamp.meetsAllTestsBudget,
            meetsG4b = stamp.meetsT3Budget,
            estimatedReferenceNanos = stamp.estimatedReferenceNanos,
            estimateSource = stamp.estimateSource.wire,
            samplesRejected = stamp.samplesRejected,
        )
    }
}
