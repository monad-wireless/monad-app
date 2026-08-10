package sk.martinvanco.monad.lab.domain

import android.content.Context
import com.google.ar.core.ArCoreApk
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android room-scan module — ARCore Depth.
 *
 * **What this is for.** Room geometry, fed into the PostGIS site model and the ray-traced channel
 * simulator. Not people-counting: depth is short-range, occluded by the very bodies it would be
 * counting, and privacy-loaded in a way a device-free RF method deliberately is not.
 *
 * **Why Android reports the weaker token.** There is no Android equivalent of ARKit's
 * scene-reconstruction check. A handful of devices ship a ToF sensor, ARCore's Depth API works on
 * many more by inference from motion, and no system flag distinguishes "produces a floor-plan-grade
 * mesh" from "can estimate depth well enough to occlude a virtual cat". Claiming
 * [Capability.LIDAR_MESH] on that basis would hand a device a room-scan quest it cannot satisfy and
 * yield a session that looks complete with an unusable artefact — the exact failure this whole
 * subsystem exists to prevent. So Android offers [Capability.DEPTH_COARSE] and the mesh-grade token
 * is left to platforms that can answer honestly.
 *
 * The capture below is therefore deliberately conservative: it establishes that ARCore is installed
 * and supported, and reports what it found. Turning a depth stream into a registered mesh needs a
 * rendering session with tracked camera poses, which cannot be driven headlessly from here and is
 * left to the dedicated scan Activity.
 */
class RoomScanModuleAndroid(
    private val context: Context = ContextProvider.getContext(),
) : LabSensorModule {

    override val id: String = "room-scan"

    override val capability: String = Capability.DEPTH_COARSE

    override suspend fun probe(): LabSensorModule.Availability = withContext(Dispatchers.IO) {
        runCatching {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                    LabSensorModule.Availability.Available

                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                ->
                    LabSensorModule.Availability.Unsupported(
                        "ARCore is supported but not installed or out of date"
                    )

                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                    LabSensorModule.Availability.Unsupported("device has no ARCore depth support")

                else ->
                    LabSensorModule.Availability.Unsupported("ARCore availability unknown")
            }
        }.getOrElse {
            // A missing ARCore class or a dead service must read as "not available", never as a
            // crash mid-session.
            LabSensorModule.Availability.Unsupported("ARCore unavailable: ${it.message}")
        }
    }

    override suspend fun capture(request: SensorRequest): Result<SensorCapture> {
        val availability = probe()
        if (availability !is LabSensorModule.Availability.Available) {
            val reason = (availability as? LabSensorModule.Availability.Unsupported)?.reason
                ?: "unavailable"
            Napier.w("[lab] room-scan refused: $reason")
            return Result.failure(IllegalStateException("room-scan unavailable: $reason"))
        }

        val label = request.config?.get("scan_label")?.jsonPrimitive?.contentOrNullSafe()
            ?: "room"

        // Honest boundary: ARCore is present and could scan, but a registered mesh needs a
        // foreground AR session with tracked poses. Rather than return an empty artefact that
        // would look like a completed scan, this fails with a reason the operator can act on.
        return Result.failure(
            UnsupportedOperationException(
                "ARCore is available for '$label' but headless mesh capture is not implemented; " +
                    "run the scan Activity on this device, or use an ARKit handset for " +
                    "mesh-grade geometry"
            )
        )
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
