package sk.martinvanco.monad.facts.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import monad.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import sk.martinvanco.monad.facts.data.FactDeckDto
import sk.martinvanco.monad.facts.data.FactDto

/**
 * The reading matter a participant gets while holding still (IP-146).
 *
 * A probe dwell is thirty seconds and it cannot be shortened — thirty seconds is near the
 * *ceiling* of a self-consistent CSI fingerprint, not a padding figure. So the interval is fixed
 * and the only question is what fills it. This deck fills it with the Foundation cards, which are
 * the part of the corpus that has been curated hardest.
 *
 * Loaded once per process and held. 209 facts is about 110 KB of JSON, parsed in one pass off the
 * main thread, and a dwell must never wait on a parse.
 */
class FactDeck {

    private var loaded: List<FactDto>? = null

    /** Every fact in the bundle. Empty when the resource is missing or unparseable. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun all(): List<FactDto> {
        loaded?.let { return it }
        val facts = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Res.readBytes("files/monad-facts.json")
                JSON.decodeFromString<FactDeckDto>(bytes.decodeToString()).facts
            }.getOrDefault(emptyList())
            // Swallowed on purpose, and this is the one place in the app where that is right: a
            // fact deck that fails to load costs a participant some reading. Letting it propagate
            // would cost the measurement, because the throw would land inside the step that is
            // recording the dwell.
        }
        loaded = facts
        return facts
    }

    /**
     * A running order for one dwell, easter eggs salted through it.
     *
     * Not a plain shuffle. Sixteen of the 209 facts are curator-marked eggs, so a plain shuffle
     * puts one in front of a participant roughly every thirteenth panel — which for somebody who
     * walks twenty points and reads sixty panels means four eggs if they are lucky, and it is the
     * eggs that make them come back. So the order is built to a ratio instead: every
     * [EGG_INTERVAL]-th slot is an egg while eggs remain.
     *
     * A meta slot (IP-151) — one panel about the participant, rendered from live state — goes in
     * at [MetaFacts.SLOT], never first, at most once, and it does not count towards the egg
     * interval, so the eggs still land on every third *curated* panel.
     *
     * @param count how many panels the dwell has room for.
     * @param metaStep the quest step number when the dwell wants a meta slot, or null for none.
     */
    fun runningOrder(facts: List<FactDto>, count: Int, metaStep: Int? = null): List<FactDto> {
        if (facts.isEmpty() || count <= 0) return emptyList()
        val eggs = facts.filter { it.surprise }.shuffled().toMutableList()
        val rest = facts.filterNot { it.surprise }.shuffled().toMutableList()
        val out = mutableListOf<FactDto>()
        var metaPlaced = false
        while (out.size < count && (eggs.isNotEmpty() || rest.isNotEmpty())) {
            if (metaStep != null && !metaPlaced && out.size == MetaFacts.SLOT) {
                out += MetaFacts.slot(metaStep)
                metaPlaced = true
                continue
            }
            val curated = out.size - (if (metaPlaced) 1 else 0)
            val wantEgg = (curated + 1) % EGG_INTERVAL == 0
            val pool = when {
                wantEgg && eggs.isNotEmpty() -> eggs
                rest.isNotEmpty() -> rest
                else -> eggs
            }
            // `removeAt(0)`, not `removeFirst()`. Kotlin compiles the latter to
            // `java.util.SequencedCollection.removeFirst`, which is JDK 21 and Android API 35 —
            // so it throws `NoSuchMethodError` on every device this app actually ships to. The
            // unit tests caught it; a device would have caught it inside a recording step.
            out += pool.removeAt(0)
        }
        return out
    }

    companion object {
        /** Every third panel is an easter egg while any remain unshown in this order. */
        const val EGG_INTERVAL = 3

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
