package sk.martinvanco.monad.lab.domain

import android.content.Context
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android visual-inertial odometry — **not implemented**, and this file exists to say so precisely.
 *
 * ARCore can produce the same six-degree-of-freedom pose stream ARKit does, so this is a gap in the
 * app and not a limit of the platform. What it needs is the piece iOS does not: an ARCore `Session`
 * must be driven from a GL surface, because `session.update()` is only legal on the thread holding
 * the texture the camera writes into. There is no `currentFrame` to poll from a coroutine. That is a
 * rendering Activity, and it is a separate piece of work from the walk this file serves.
 *
 * The refusal is deliberately loud rather than silent. [probe] reports whether ARCore *could* have
 * done it, so an operator reading the console can tell "this handset cannot track" from "this build
 * cannot track on this handset" — and only the second one is a reason to reach for the iPhone.
 *
 * Until then a walk on Android still records its BLE identity frame and its scanned waypoints. It
 * gets discrete surveyed points with no line between them, which is a weaker fingerprinting dataset,
 * not an empty one.
 */
actual class PoseTracker actual constructor() {

    private val context: Context get() = ContextProvider.getContext()

    private val _samples = MutableSharedFlow<PoseSample>(replay = 0, extraBufferCapacity = 1)
    actual val samples: Flow<PoseSample> = _samples.asSharedFlow()

    /** Never emits — there is no session to be interrupted. */
    private val _events = MutableSharedFlow<PoseTrackerEvent>(replay = 0, extraBufferCapacity = 1)
    actual val events: Flow<PoseTrackerEvent> = _events.asSharedFlow()

    /**
     * Permanently null: there is no tracker on Android, so there are no frames to decode a card
     * from. The console reads null as "this device cannot see cards" and keeps manual entry, which
     * is the same path every Android build has always taken.
     */
    actual val seenCard: Flow<String?> = flowOf(null)

    /** Nothing to preview: no tracker means no camera session to render. */
    actual fun previewHandle(): Any? = null

    actual suspend fun probe(): LabSensorModule.Availability = withContext(Dispatchers.IO) {
        val arcore = runCatching {
            ArCoreApk.getInstance().checkAvailability(context) ==
                ArCoreApk.Availability.SUPPORTED_INSTALLED
        }.getOrDefault(false)
        LabSensorModule.Availability.Unsupported(
            if (arcore) {
                "ARCore is installed and capable, but this build has no ARCore pose tracker — " +
                    "it needs a GL rendering session, which iOS does not"
            } else {
                "no ARCore pose support on this device"
            }
        )
    }

    actual suspend fun start(rateHz: Double, initialWorldMap: ByteArray?): Result<PoseTrackReport> {
        val reason = (probe() as? LabSensorModule.Availability.Unsupported)?.reason
            ?: "pose tracking unavailable"
        return Result.failure(UnsupportedOperationException(reason))
    }

    /** No tracker, no map. ARCore's Cloud Anchors are a different mechanism and a different design. */
    actual suspend fun snapshotWorldMap(): Result<ByteArray> = Result.failure(
        UnsupportedOperationException("no world map on Android in this build")
    )

    /**
     * Empty, not a failure. A handset with no tracker never had a mesh to have changed, so an empty
     * change log is the correct record — a failure here would put a spurious error in the session log
     * on every tick of every walk.
     */
    actual suspend fun observeMesh(): List<MeshObservation> = emptyList()

    actual suspend fun snapshotMesh(): Result<MeshSnapshot> = Result.failure(
        UnsupportedOperationException(
            "no LiDAR mesh on Android in this build — ARCore Depth is short-range and has no " +
                "equivalent of ARKit's scene-reconstruction check, so a mesh from here would not be " +
                "floor-plan grade and would claim to be"
        )
    )

    actual fun stop() = Unit

    actual fun diagnostics(): List<String> = listOf(
        "pose tracking: not implemented on Android (needs an ARCore GL session)",
        "a walk on this handset records BLE identity and scanned waypoints, but no trajectory",
    )
}
