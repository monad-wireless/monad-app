package sk.martinvanco.monad.profile.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.profile.data.api.ProfileService

class ProfileScreenModel(
    private val profileService: ProfileService,
    private val authManager: AuthManager,
) : StateScreenModel<ProfileState>(ProfileState()) {

    init {
        load()
    }

    fun load() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)

            // Resolved per load rather than held, so a token refreshed elsewhere is picked up
            // and a signed-out session cannot keep serving a stale one from this model.
            val token = authManager.getCurrentUser()?.token
            if (token.isNullOrBlank()) {
                // Distinguished from a network failure on purpose. Both used to surface as
                // "could not load", which sent the reader looking at the server for a problem
                // that was on the handset.
                mutableState.value = ProfileState(
                    stats = null,
                    isLoading = false,
                    error = "Sign in again to see your contribution.",
                )
                return@launch
            }

            runCatching { profileService.getStats(token) }
                .onSuccess { stats ->
                    mutableState.value = ProfileState(stats = stats, isLoading = false)
                }
                .onFailure { failure ->
                    // The error replaces the numbers rather than sitting beside stale ones. A
                    // dashboard showing yesterday's total under a red banner is the screenshot
                    // somebody believes.
                    mutableState.value = ProfileState(
                        stats = null,
                        isLoading = false,
                        error = failure.message ?: "Could not load your stats",
                    )
                }
        }
    }
}
