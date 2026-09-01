package sk.martinvanco.monad.profile.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.profile.data.api.ProfileService

class ProfileScreenModel(
    private val profileService: ProfileService,
) : StateScreenModel<ProfileState>(ProfileState()) {

    init {
        load()
    }

    fun load() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)
            runCatching { profileService.getStats() }
                .onSuccess { stats ->
                    mutableState.value = ProfileState(stats = stats, isLoading = false)
                }
                .onFailure { failure ->
                    // The error replaces the numbers rather than sitting beside stale ones.
                    // A profile showing yesterday's total under a red banner is the shape
                    // somebody screenshots and believes.
                    mutableState.value = ProfileState(
                        stats = null,
                        isLoading = false,
                        error = failure.message ?: "Could not load your stats",
                    )
                }
        }
    }
}
