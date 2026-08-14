package sk.martinvanco.monad.device.presentation

import sk.martinvanco.monad.device.data.dto.DeviceDetailDto
import sk.martinvanco.monad.device.data.dto.DeviceQuestDto

/**
 * What the device screen shows after a label scan (IP-128).
 *
 * `isSignedIn` drives the wall placement and nothing else: a signed-out visitor
 * sees the *same* device and the *same* quests, and only the action changes.
 * Guests are read-only by decision, and the ask lands after the payoff rather
 * than in front of it.
 */
data class DeviceState(
    val slug: String,
    val detail: DeviceDetailDto? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSignedIn: Boolean = false,
    /** Set when a deep link named one quest (`?q=`); it is surfaced first. */
    val focusQuestId: String? = null,
) {
    val quests: List<DeviceQuestDto>
        get() = detail?.quests.orEmpty()

    /** The `?q=` quest if it is still offered here, else null — an expired or
     *  unknown id degrades to the full device view rather than an error. */
    val focusQuest: DeviceQuestDto?
        get() = focusQuestId?.let { id -> quests.firstOrNull { it.id == id } }

    val title: String
        get() = detail?.device?.label?.takeIf { it.isNotBlank() } ?: slug

    val isRetired: Boolean
        get() = detail?.device?.isActive == false
}
