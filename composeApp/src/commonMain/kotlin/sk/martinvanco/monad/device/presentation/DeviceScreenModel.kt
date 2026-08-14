package sk.martinvanco.monad.device.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.device.data.api.DeviceService

/**
 * Loads the device behind a scanned label (IP-128).
 *
 * Two things it does NOT do, both deliberate:
 *
 * - **It does not require a session.** `KtorClient` is configured with
 *   `expectSuccess = true`, so a 401 arrives as a thrown
 *   `ClientRequestException`; the device endpoint is public precisely so a
 *   stranger never meets one, and the catch below keeps a signed-out reader on
 *   a working screen if anything else goes wrong.
 * - **It does not start anything.** Arming a quest is an authenticated write and
 *   belongs to the quest flow. This screen is the read.
 */
class DeviceScreenModel(
    private val deviceService: DeviceService,
    private val userRepository: UserRepository,
    slug: String,
    questId: String?,
) : StateScreenModel<DeviceState>(DeviceState(slug = slug, focusQuestId = questId)) {

    init {
        load()
    }

    fun onEvent(event: DeviceEvent) {
        when (event) {
            is DeviceEvent.Retry -> load()
        }
    }

    private fun load() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)

            // Read the session first so the screen can render its correct action
            // even if the device read then fails.
            val signedIn = runCatching { userRepository.getCurrentUser() != null }.getOrDefault(false)

            try {
                val detail = deviceService.getDevice(mutableState.value.slug)
                mutableState.value = mutableState.value.copy(
                    detail = detail,
                    isLoading = false,
                    error = null,
                    isSignedIn = signedIn,
                )
            } catch (e: ClientRequestException) {
                val message = when (e.response.status.value) {
                    404 -> "This code does not match any node in the fleet."
                    else -> "Could not read this node."
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = message,
                    isSignedIn = signedIn,
                )
            } catch (e: ServerResponseException) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "The server is having a moment. Try again shortly.",
                    isSignedIn = signedIn,
                )
            } catch (e: Exception) {
                // Most likely offline — a lab basement is a realistic place to be
                // holding this phone, so the wording says what to do about it.
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "No connection. This node's page needs the network.",
                    isSignedIn = signedIn,
                )
            }
        }
    }
}
