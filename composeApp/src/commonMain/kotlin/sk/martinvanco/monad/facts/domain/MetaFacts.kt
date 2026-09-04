package sk.martinvanco.monad.facts.domain

import sk.martinvanco.monad.facts.data.FactDto

/**
 * Meta-facts — the one panel per dwell that is about the participant (IP-151).
 *
 * Every other panel is a curated paragraph from the vault. This one is a template over the quest's
 * own state, rendered at display time from the app's string resources, so every number in it is
 * true at the moment it is read: how long this person has held still, which step they are on,
 * how many panels they have read. That is the whole trick behind making the dwell occasionally
 * funny without inventing anything — the joke is the true number, and the phrasing is dry.
 *
 * Three rules, each enforced here rather than left to the caller:
 *
 * - **At most one per dwell**, and **never the first panel**. The first panel is where a participant
 *   learns what the pane is for; a joke about them before that reads as the app not knowing it is
 *   being watched.
 * - **Never an egg.** Eggs are curator-marked cards. A meta-fact does not count against the egg
 *   interval and is never dressed as one.
 * - **Never shown without its state.** A template rendered against a stale or missing counter is a
 *   lie about the participant, so the panel renders a meta slot only when it has a [DwellState],
 *   and otherwise skips it.
 *
 * The slot travels through the running order as an ordinary [FactDto] with flavour [FLAVOUR] and a
 * `meta:` id, so [FactDeck.runningOrder] stays one list and the panel decides how to draw it.
 */
object MetaFacts {

    const val FLAVOUR = "meta"

    /** Where the meta slot sits in a running order: the second panel. */
    const val SLOT = 1

    /** The templates. Chosen by step number so consecutive probes do not repeat one. */
    enum class Template(val id: String) {
        HELD_STILL("meta:held-still"),
        STEP("meta:step"),
        PANELS("meta:panels"),
        ;

        companion object {
            fun forStep(stepNumber: Int): Template = entries[((stepNumber - 1).coerceAtLeast(0)) % entries.size]

            fun byId(id: String): Template? = entries.firstOrNull { it.id == id }
        }
    }

    /** The placeholder the running order carries. The panel fills it at display time. */
    fun slot(stepNumber: Int): FactDto = FactDto(
        id = Template.forStep(stepNumber).id,
        cardId = "meta",
        deck = "meta",
        title = "",
        flavour = FLAVOUR,
        body = "",
        surprise = false,
        quip = null,
    )

    fun isMeta(fact: FactDto): Boolean = fact.flavour == FLAVOUR
}

/**
 * What the panel knows about this participant right now. Every field is read off the step's own
 * state, never estimated.
 *
 * @param stepNumber position of this step in the quest, 1-based.
 * @param dwellSeconds the dwell this step asks for.
 * @param heldSeconds how long the participant has held still so far in this dwell.
 * @param panelsShown how many panels have been shown in this dwell, including the current one.
 */
data class DwellState(
    val stepNumber: Int,
    val dwellSeconds: Int,
    val heldSeconds: Int,
    val panelsShown: Int,
)
