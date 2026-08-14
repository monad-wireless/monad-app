package sk.martinvanco.monad.device.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The public device record behind a scanned label (IP-128).
 *
 * Mirrors `GET /api/device/{slug}` on the backend, which is UNAUTHENTICATED and
 * deliberately carries no per-participant state: what the node is, which quests
 * it arms, and whether each is runnable *here and now* for reasons that are true
 * of the world (window, arming, node capturing) rather than of the reader.
 *
 * Guests are read-only by design, so this is the whole payload a signed-out
 * scanner ever sees. The account wall lands later, when they try to earn.
 */
@Serializable
data class DeviceDetailDto(
    val device: DeviceDto,
    val quests: List<DeviceQuestDto> = emptyList(),
    val fleet: FleetDto = FleetDto(),
)

@Serializable
data class DeviceDto(
    val slug: String,
    val label: String? = null,
    val location: String? = null,
    @SerialName("site_ref") val siteRef: String? = null,
    @SerialName("public_blurb") val publicBlurb: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("commissioned_at") val commissionedAt: String? = null,
)

@Serializable
data class DeviceQuestDto(
    val id: String,
    val name: String,
    val description: String = "",
    val points: Double = 0.0,
    @SerialName("estimated_duration") val estimatedDuration: Int? = null,
    @SerialName("required_capabilities") val requiredCapabilities: List<String> = emptyList(),
    val availability: AvailabilityDto = AvailabilityDto(),
)

/**
 * Why a quest cannot be started, in a closed vocabulary the UI branches on.
 *
 * Free text here would silently become an unreachable UI state the first time
 * someone rephrased a message on the server.
 */
@Serializable
data class AvailabilityDto(
    val available: Boolean = false,
    val reason: String? = null,
    @SerialName("retry_at") val retryAt: String? = null,
) {
    companion object {
        const val REASON_COOLDOWN = "cooldown"
        const val REASON_IN_PROGRESS = "in_progress"
        const val REASON_NODE_IDLE = "node_idle"
        const val REASON_WINDOW_CLOSED = "window_closed"
        const val REASON_NOT_ARMED = "not_armed"
        const val REASON_DEVICE_INACTIVE = "device_inactive"
    }
}

@Serializable
data class FleetDto(
    /** Passport denominator, so the app never hardcodes the fleet size. */
    @SerialName("active_devices") val activeDevices: Int = 0,
)
