package sk.martinvanco.monad.home.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuestListResponseDto(
    val quests: List<QuestDto>
)

@Serializable
data class QuestDto(
    val id: String,
    val name: String,
    val description: String,
    val points: Float,
    val estimatedDuration: Int?,
    val numberOfSteps: Int,
    /**
     * Who the quest is for: `public` or `operator` (IP-145).
     *
     * The backend withholds `operator` rows from everybody but a superadmin, so an ordinary
     * participant only ever sees `public` here. The field exists for the operator's own phone,
     * which used to receive both kinds in one list with nothing to tell them apart — so a take
     * meant for the person running the session sat between two student quests.
     *
     * Defaulted, because an older backend does not send it and a missing audience must read as the
     * safe, ordinary case rather than fail the whole list.
     */
    val audience: String = AUDIENCE_PUBLIC
) {
    val isOperatorOnly: Boolean get() = audience == AUDIENCE_OPERATOR

    companion object {
        const val AUDIENCE_PUBLIC = "public"
        const val AUDIENCE_OPERATOR = "operator"
    }
}
