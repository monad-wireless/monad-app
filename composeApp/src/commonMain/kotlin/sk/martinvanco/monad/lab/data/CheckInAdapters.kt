package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.domain.marker.MarkerCode
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.lab.domain.AdvertisePlan
import sk.martinvanco.monad.lab.domain.CheckInPlace
import sk.martinvanco.monad.lab.domain.LabSiteSource
import sk.martinvanco.monad.lab.domain.PlaceDirectory
import sk.martinvanco.monad.quests.data.dto.ProbeConfig
import sk.martinvanco.monad.quests.data.dto.QuestDetailDto
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.data.dto.TaskType

/**
 * Where this handset is, out of the cached lab bundle.
 *
 * Reads the cached value rather than fetching: check-in happens the instant a camera decodes a
 * card, and a participant standing in a doorway must not wait on an HTTP round trip that a lab
 * network routinely cannot complete. `LabConfigService` already persists the last good bundle and
 * refreshes it elsewhere, so the worst case here is yesterday's site slug — which for a fixed
 * deployment is the right answer anyway.
 */
class LabConfigSiteSource(
    private val configService: LabConfigService,
) : LabSiteSource {

    override fun siteSlug(): String = configService.config.value.site

    override fun advertisePlan(): AdvertisePlan = configService.config.value.advertise
}

/**
 * What a scanned card is called and which room it is in, out of the live quest set.
 *
 * ## Why the quests and not the placement mirror
 *
 * The backend has a `lab_placements` table that says exactly this, and it is the better source. It
 * has never been synced — `lab_placements_read` for `fiit-ground-0` answered `synced_at: null`,
 * 0 cards, 0 nodes on 2026-09-18 — so today it would resolve nothing. Meanwhile a live quest's
 * `probe` targets already carry a `label` and a `room` for every card they accept, resolved out of
 * PostGIS by the generator that wrote the quest. That is a real mapping this app can reach now.
 *
 * When the mirror is populated, a second implementation of [PlaceDirectory] replaces this one and
 * nothing in the check-in path changes.
 *
 * ## An unnamed card is a correct answer
 *
 * A participant may well sit beside a card no current quest asks for. They are still a person in a
 * real place, so the check-in proceeds and the card shows its own code. Inventing a room for it
 * would be worse than saying `MONAD-FP-07`, which is at least checkable against the thing in their
 * hand.
 *
 * ## The cache
 *
 * Resolving means listing the quests and then fetching each one's detail, which is N+1 requests. A
 * check-in is at most two of those per visit, but a participant who scans, moves, and scans again
 * would pay it three times in a minute for an answer that changes when a quest is re-authored —
 * days apart. So the code-to-place map is built once and held for [CACHE_MILLIS]. A failure caches
 * nothing, so the next scan tries again rather than inheriting an empty map.
 */
class QuestPlaceDirectory(
    private val questsService: QuestsService,
    private val userRepository: UserRepository,
) : PlaceDirectory {

    private val mutex = Mutex()
    private var cache: Map<String, CheckInPlace> = emptyMap()
    private var cachedAt: Long = 0L

    override suspend fun resolve(cardKey: String): CheckInPlace {
        val known = places()[cardKey]
        if (known != null) return known
        return CheckInPlace(key = cardKey)
    }

    private suspend fun places(): Map<String, CheckInPlace> = mutex.withLock {
        val now = currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < CACHE_MILLIS) return cache

        val built = runCatching { build() }.getOrElse {
            Napier.i("[check-in] card names unavailable, using codes: ${it.message}")
            return emptyMap()
        }
        if (built.isNotEmpty()) {
            cache = built
            cachedAt = now
        }
        built
    }

    private suspend fun build(): Map<String, CheckInPlace> {
        val token = userRepository.getCurrentUser()?.token ?: return emptyMap()
        val listed = questsService.getActiveQuests(token).quests
        val places = mutableMapOf<String, CheckInPlace>()
        for (summary in listed) {
            val detail = runCatching {
                QuestDetailDto.fromResponse(questsService.getQuestDetail(summary.id, token))
            }.getOrNull() ?: continue
            detail.probeTargets().forEach { target ->
                val key = MarkerCode.key(target.value)
                if (key.isEmpty()) return@forEach
                // First quest to name a card wins. Two quests naming one card with different
                // labels is an authoring question, not something to resolve by guessing here, and
                // either label is true of the same piece of card.
                places.getOrPut(key) {
                    CheckInPlace(key = key, label = target.label, room = target.room)
                }
            }
        }
        return places
    }

    private companion object {
        /** Ten minutes. Long enough that a visit costs one build, short enough to pick up a re-author. */
        const val CACHE_MILLIS = 10 * 60 * 1000L
    }
}

/** Every probe target this quest accepts, with the label and room the generator resolved. */
private fun QuestDetailDto.probeTargets() = tasks
    .asSequence()
    .filter { it.type == TaskType.PROBE }
    .mapNotNull { task ->
        runCatching { TaskConfigParser.parseConfig(TaskType.PROBE, task.config) as? ProbeConfig }
            .getOrNull()
    }
    .flatMap { it.targets }
