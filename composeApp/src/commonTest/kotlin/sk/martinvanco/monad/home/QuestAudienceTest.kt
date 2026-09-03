package sk.martinvanco.monad.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.home.data.dto.QuestDto
import sk.martinvanco.monad.home.data.dto.QuestListResponseDto
import sk.martinvanco.monad.home.presentation.HomeState
import sk.martinvanco.monad.home.presentation.model.QuestCardDt

/**
 * Operator takes never appear among student quests (IP-145).
 *
 * The backend withholds them from a participant, so this split is only ever visible on the
 * operator's own phone — which is precisely where the two kinds were being listed together, with
 * nothing on the card to say which was which.
 */
class QuestAudienceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun card(name: String, points: Float, operator: Boolean) = QuestCardDt(
        id = name,
        name = name,
        description = "",
        numTasks = 3,
        timeEstimateMin = 12,
        points = points,
        isOperatorOnly = operator,
    )

    private val state = HomeState(
        quests = listOf(
            card("Fingerprint the reading room", 45f, operator = false),
            card("Night baseline take", 0f, operator = true),
            card("Treasure hunt", 30f, operator = false),
        )
    )

    @Test
    fun theBoardListsOnlyParticipantQuests() {
        assertEquals(
            listOf("Fingerprint the reading room", "Treasure hunt"),
            state.participantQuests.map { it.name },
        )
    }

    @Test
    fun operatorTakesAreListedSeparately() {
        assertEquals(listOf("Night baseline take"), state.operatorQuests.map { it.name })
    }

    @Test
    fun pointsOnOfferCountOnlyWhatAStudentCanWalk() {
        assertEquals(75f, state.pointsOnOffer)
    }

    @Test
    fun aResponseWithoutAnAudienceReadsAsPublic() {
        // An older backend does not send the field. A missing audience must degrade to the safe,
        // ordinary case — never fail the list, and never hide a quest that should be shown.
        val decoded = json.decodeFromString<QuestListResponseDto>(
            """{"quests":[{"id":"a","name":"A","description":"","points":10.0,
               "estimatedDuration":5,"numberOfSteps":2}]}"""
        )
        assertFalse(decoded.quests.single().isOperatorOnly)
        assertEquals(QuestDto.AUDIENCE_PUBLIC, decoded.quests.single().audience)
    }

    @Test
    fun anOperatorAudienceIsRecognised() {
        val decoded = json.decodeFromString<QuestListResponseDto>(
            """{"quests":[{"id":"a","name":"A","description":"","points":0.0,
               "estimatedDuration":null,"numberOfSteps":2,"audience":"operator"}]}"""
        )
        assertTrue(decoded.quests.single().isOperatorOnly)
        assertTrue(QuestCardDt.fromDto(decoded.quests.single()).isOperatorOnly)
    }
}
