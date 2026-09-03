package sk.martinvanco.monad.facts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sk.martinvanco.monad.facts.data.FactDto
import sk.martinvanco.monad.facts.domain.FactDeck

/**
 * The dwell running order (IP-146).
 *
 * Tested rather than eyeballed because the failure is invisible: a plain shuffle of 209 facts puts
 * a curator-marked oddity in front of a participant about every thirteenth panel, so a walk of
 * twenty points might show one. The eggs are what make somebody come back, and "the eggs never
 * appeared" is not something a screenshot shows.
 */
class FactDeckTest {

    private val deck = FactDeck()

    private fun fact(id: String, surprise: Boolean = false) = FactDto(
        id = id,
        cardId = id.substringBefore(':'),
        deck = "core-test",
        title = "Title $id",
        flavour = if (surprise) "wild" else "foundation",
        body = "A body long enough to be worth reading on a phone panel.",
        surprise = surprise,
    )

    private val facts = List(20) { fact("plain-$it") } + List(5) { fact("egg-$it", surprise = true) }

    @Test
    fun everyThirdPanelIsAnEasterEgg() {
        val order = deck.runningOrder(facts, count = 9)
        assertEquals(9, order.size)
        assertEquals(
            listOf(false, false, true, false, false, true, false, false, true),
            order.map { it.surprise },
        )
    }

    @Test
    fun noFactRepeatsWithinOneDwell() {
        val order = deck.runningOrder(facts, count = 12)
        assertEquals(order.size, order.map { it.id }.toSet().size)
    }

    @Test
    fun fallsBackToOrdinaryFactsWhenTheEggsRunOut() {
        val order = deck.runningOrder(List(9) { fact("plain-$it") }, count = 9)
        assertEquals(9, order.size)
        assertTrue(order.none { it.surprise })
    }

    @Test
    fun fallsBackToEggsWhenTheOrdinaryFactsRunOut() {
        val order = deck.runningOrder(List(3) { fact("egg-$it", surprise = true) }, count = 3)
        assertEquals(3, order.size)
        assertTrue(order.all { it.surprise })
    }

    @Test
    fun askingForMorePanelsThanExistReturnsWhatExists() {
        val order = deck.runningOrder(List(4) { fact("plain-$it") }, count = 30)
        assertEquals(4, order.size)
    }

    @Test
    fun anEmptyDeckProducesNoPanels() {
        // The bundle failed to load. The dwell must still run — the countdown and the recording
        // are the step's job, and the reading matter is not.
        assertTrue(deck.runningOrder(emptyList(), count = 5).isEmpty())
        assertTrue(deck.runningOrder(facts, count = 0).isEmpty())
    }
}
