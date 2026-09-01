package sk.martinvanco.monad.profile.presentation

import sk.martinvanco.monad.profile.data.dto.ProfileStatsDto

data class ProfileState(
    val stats: ProfileStatsDto? = null,
    val isLoading: Boolean = true,
    /**
     * Shown instead of the numbers, never beside a zero. A failed fetch rendered as
     * "0 points, 0 dwells" reads as "you have contributed nothing", which is a wrong
     * answer rather than a missing one.
     */
    val error: String? = null,
)
