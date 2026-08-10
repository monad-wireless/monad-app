package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import platform.ARKit.ARWorldTrackingConfiguration
import platform.ARKit.ARSceneReconstructionMesh
import platform.NearbyInteraction.NISession

/**
 * iOS room-scan module — ARKit scene reconstruction.
 *
 * iOS is the platform that can answer the depth question *honestly*.
 * `ARWorldTrackingConfiguration.supportsSceneReconstruction` is true on exactly the LiDAR devices
 * (iPhone 12 Pro and later Pro models, iPad Pro 2020 onwards) and false everywhere else — no
 * inference, no guessing from a model string. That is why [Capability.LIDAR_MESH] is an iOS-only
 * token today: Android has no equivalent check, and a token that might be wrong is worse than a
 * token that is absent.
 */
class RoomScanModuleIos : LabSensorModule {

    override val id: String = "room-scan"

    override val capability: String = Capability.LIDAR_MESH

    override suspend fun probe(): LabSensorModule.Availability =
        if (ARWorldTrackingConfiguration.supportsSceneReconstruction(ARSceneReconstructionMesh)) {
            LabSensorModule.Availability.Available
        } else {
            LabSensorModule.Availability.Unsupported("device has no LiDAR scene reconstruction")
        }

    override suspend fun capture(request: SensorRequest): Result<SensorCapture> {
        val availability = probe()
        if (availability !is LabSensorModule.Availability.Available) {
            val reason = (availability as? LabSensorModule.Availability.Unsupported)?.reason
                ?: "unavailable"
            Napier.w("[lab] room-scan refused: $reason")
            return Result.failure(IllegalStateException("room-scan unavailable: $reason"))
        }

        // Boundary, stated rather than papered over: mesh anchors only arrive through a running
        // ARSession with a delegate and a camera feed, which is a foreground UIViewController — not
        // something this suspend function can conjure. Returning an empty mesh here would be a
        // scan step that "succeeded" with no geometry, which is precisely the silent-empty outcome
        // the instrument is designed never to produce.
        return Result.failure(
            UnsupportedOperationException(
                "LiDAR is available; mesh capture requires the foreground scan view controller"
            )
        )
    }
}

/**
 * iOS UWB ranging — NearbyInteraction.
 *
 * `NISession.isSupported` covers the U1/U2 handsets. As on Android this is a two-ended measurement:
 * without a peer token from an anchor there is nothing to range against, and the module says so
 * instead of producing an empty log.
 */
class UwbRangingModuleIos : LabSensorModule {

    override val id: String = "uwb-range"

    override val capability: String = Capability.UWB_RANGING

    override suspend fun probe(): LabSensorModule.Availability =
        if (NISession.isSupported()) {
            LabSensorModule.Availability.Available
        } else {
            LabSensorModule.Availability.Unsupported("device has no ultra-wideband radio")
        }

    override suspend fun capture(request: SensorRequest): Result<SensorCapture> {
        val availability = probe()
        if (availability !is LabSensorModule.Availability.Available) {
            val reason = (availability as? LabSensorModule.Availability.Unsupported)?.reason
                ?: "unavailable"
            return Result.failure(IllegalStateException("uwb-range unavailable: $reason"))
        }
        return Result.failure(
            UnsupportedOperationException(
                "UWB is available; ranging needs a discovery token exchanged with an anchor"
            )
        )
    }
}

actual fun labSensorModules(): List<LabSensorModule> =
    listOf(RoomScanModuleIos(), UwbRangingModuleIos())
