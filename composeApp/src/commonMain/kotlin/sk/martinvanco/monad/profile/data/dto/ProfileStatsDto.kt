package sk.martinvanco.monad.profile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `GET /api/me/stats` returns (IP-145).
 *
 * Three blocks, and the last is the one worth having. `pointsTotal` and `history` are the
 * game; [ContributionDto] answers "what did my walking produce", which is the only half whose
 * value does not rest on gamification evidence the corpus does not hold.
 */
@Serializable
data class ProfileStatsDto(
    @SerialName("points_total") val pointsTotal: Float = 0f,
    @SerialName("quests_completed") val questsCompleted: Int = 0,
    val contribution: ContributionDto = ContributionDto(),
    val history: List<HistoryEntryDto> = emptyList(),
)

/**
 * Counted over COMPLETED steps only. An abandoned dwell produced no usable window, so
 * including it would tell a participant they contributed a measurement that does not exist.
 */
@Serializable
data class ContributionDto(
    val dwells: Int = 0,
    @SerialName("dwell_seconds") val dwellSeconds: Int = 0,
    @SerialName("distinct_points") val distinctPoints: Int = 0,
    /**
     * The surveyed points this person has stood at, by key. Drawn as the coverage plan.
     * Sorted server-side so two people who walked the same set request the same image.
     */
    @SerialName("points_visited") val pointsVisited: List<String> = emptyList(),
)

@Serializable
data class HistoryEntryDto(
    val quest: String,
    @SerialName("completed_at") val completedAt: String? = null,
    /**
     * The FROZEN award, so a quest re-valued later does not change what this walk was worth.
     * Null on an enrollment that predates the ledger.
     */
    val points: Float? = null,
)
