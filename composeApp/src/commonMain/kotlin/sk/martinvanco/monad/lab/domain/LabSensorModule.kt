package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.JsonObject

/**
 * An optional sensor the instrument can call on when the hardware is there.
 *
 * The fleet is heterogeneous by nature — an operator's LiDAR-equipped iPhone Pro, a participant's
 * mid-range Android, a UWB-capable handset used as a mobile anchor — and the design rule is that a
 * capability the device lacks must never produce a *quieter* session, only a *withheld* one. So a
 * module answers two separate questions, and both matter:
 *
 *  * [capability] — the static token the backend filters the quest catalogue on, so a quest needing
 *    this sensor is never even offered to a handset that cannot run it.
 *  * [probe] — the runtime truth, which is not the same thing. ARCore can be installed and still
 *    refuse the session; UWB can be present and switched off; a permission can be revoked between
 *    the catalogue fetch and the run. A probe that says [Availability.Unsupported] aborts the step
 *    with the reason attached rather than recording nothing and calling it a take.
 *
 * Modules never write to the sample streams directly. They return a [SensorCapture], and the caller
 * decides what to persist — which keeps a sensor from being able to corrupt the radio timeline it
 * is only a passenger on.
 */
interface LabSensorModule {
    /** Stable identifier, used in step config and in artefact filenames. */
    val id: String

    /** Capability token this module requires; see [Capability]. */
    val capability: String

    /** Runtime availability, distinct from the static capability token. */
    suspend fun probe(): Availability

    /**
     * Run one capture.
     *
     * Implementations must be cancellable and must never block past [SensorRequest.timeoutMillis];
     * a sensor that hangs would strand a participant mid-session with no way forward.
     */
    suspend fun capture(request: SensorRequest): Result<SensorCapture>

    sealed interface Availability {
        data object Available : Availability

        /**
         * The hardware or software is not there. [reason] is shown to the operator and written into
         * the session record, because "the scan step did nothing" is only debuggable if the device
         * said why at the time.
         */
        data class Unsupported(val reason: String) : Availability

        /** Present, but a runtime permission is missing. */
        data class NeedsPermission(val permission: String) : Availability
    }
}

/** What a step asks a module to do. */
data class SensorRequest(
    val sessionId: String,
    /** The step's own config, so a quest can tune a module without an app release. */
    val config: JsonObject? = null,
    val timeoutMillis: Long = 60_000,
)

/**
 * The result of one capture.
 *
 * [payload] is an optional artefact — a mesh, a range log — uploaded alongside the session's TSV
 * streams under [filename]. [summary] lands in the marker so the value of the capture is visible in
 * the session record without downloading the artefact: a room scan that returned 12 vertices is a
 * failed scan, and the operator should be able to see that on the phone.
 */
data class SensorCapture(
    val moduleId: String,
    val summary: Map<String, String>,
    val payload: ByteArray? = null,
    val filename: String? = null,
) {
    // ByteArray in a data class: identity equals would make two identical captures unequal, which
    // breaks the obvious test assertions. Compared by content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorCapture) return false
        return moduleId == other.moduleId &&
            summary == other.summary &&
            filename == other.filename &&
            (payload?.contentEquals(other.payload) ?: (other.payload == null))
    }

    override fun hashCode(): Int {
        var result = moduleId.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * The modules this platform can offer.
 *
 * Every module is compiled into every build — the decision is made at runtime, not at build time,
 * so one binary serves the whole heterogeneous fleet and a device that gains a capability (ARCore
 * installed, UWB switched on) starts qualifying for quests without a new release.
 */
expect fun labSensorModules(): List<LabSensorModule>
