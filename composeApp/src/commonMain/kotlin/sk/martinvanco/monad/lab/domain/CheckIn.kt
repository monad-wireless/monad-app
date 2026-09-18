package sk.martinvanco.monad.lab.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import sk.martinvanco.monad.core.domain.marker.MarkerCode

/**
 * Check-in, as a participant experiences it: scan whatever card is nearest, and the phone starts
 * counting the time you spent there.
 *
 * This replaces a surface that asked for a specific printed code. The old one only accepted
 * `monad://ground-truth/v1?session=…&zone=…`, a payload that exists on no card currently taped to
 * anything: the 35 cards in the field carry `https://monad.dubec.dev/m/MONAD-FP-07` and friends. So
 * the doorway code and the fingerprint card were two vocabularies, and a participant holding a
 * phone next to a real card was told "that is not a MonadCount check-in code".
 *
 * ## What a scan now supplies, and what it does not
 *
 * A card supplies its **identity** and nothing else. It does not carry a session, a direction or a
 * site, and it must not: a card is re-laid between arms, and a payload that named a session would
 * be wrong the first time one moved. So:
 *
 * | Field | Where it comes from |
 * |---|---|
 * | `zone_id` | the folded card key, e.g. `monad-fp-07` — the join key the backend, the portal and `quest-check` already share |
 * | `lab_session_id` | [CheckInSessionId], derived from the lab bundle's `site` and the local date |
 * | `site` | the lab bundle |
 * | `direction` | the app's own state: a first scan is `in`, the same card again is `out` |
 * | label / room | a live quest's probe target, when one names this card ([PlaceDirectory]) |
 *
 * ## Why the session id is the site and the day
 *
 * `lab_session_id` is an opaque `string(128)` on the backend with exactly two consumers: the
 * room-wide tally (`GET /api/lab/ground-truth/{id}`) and the analysis join. Nothing validates it
 * and nothing mints it, so the only requirement is that **every phone in the room agrees** — a
 * per-phone id would turn one room count into a dozen counts of one.
 *
 * `<site>-<YYYY-MM-DD>` satisfies that with no server round trip, no bundle field and nothing to
 * deploy: every handset reads the same `site` out of the same bundle, and they are standing in the
 * same building on the same day. Two properties are worth stating because they are limits, not
 * oversights:
 *
 * 1. **A session that crosses local midnight splits into two ids.** That is accepted rather than
 *    worked around. The alternative — carrying a start date forward — would make two phones that
 *    launched on either side of midnight disagree, which is the one failure this scheme exists to
 *    avoid. The auto-close ceiling ([CheckInPolicy.MAX_DURATION_MILLIS]) means an open check-in is
 *    a few hours at most, so the split costs at most one boundary crossing.
 * 2. **The date is the device's local date.** Two phones in one room share a time zone; two phones
 *    in different countries are not in one room, and there is no room count to disagree about.
 */
data class CheckInPlace(
    /** The folded card key. Always present — it is what was scanned. */
    val key: String,
    /** A quest's name for this card, when one names it. Empty otherwise. */
    val label: String = "",
    /** The room the card is in, when a quest's probe target says so. Empty otherwise. */
    val room: String = "",
) {
    /**
     * What to print on the card and in the notification.
     *
     * Falls back through label, then room, then the code itself. Never invents a place name: a
     * card the app cannot resolve says `MONAD-FP-07`, which is true and checkable against the
     * thing in the participant's hand, rather than a plausible room that might be the wrong one.
     */
    val display: String
        get() = when {
            label.isNotBlank() -> label
            room.isNotBlank() -> room
            else -> MarkerCode.display(key)
        }

    /** True when nothing in the live quest set names this card. */
    val isUnknownCard: Boolean get() = label.isBlank() && room.isBlank()
}

/**
 * Where a card is, when anything knows.
 *
 * A port rather than a service, because `lab/domain` may not name a data layer (`LabBoundaryTest`)
 * and the answer today comes from the quest set — a live quest's `probe` targets carry a resolved
 * `label` and `room` for every card they accept, which is the only code-to-room mapping the app can
 * currently reach. The backend's own `lab_placements` mirror would be the better source and has
 * never been synced (`synced_at: null`, 0 cards, checked 2026-09-18), so an implementation over it
 * can replace this one without the check-in path noticing.
 *
 * Returning an unnamed [CheckInPlace] is a correct answer, not a failure: a participant may sit
 * beside a card no current quest asks for, and the check-in is still a real person in a real place.
 */
interface PlaceDirectory {
    suspend fun resolve(cardKey: String): CheckInPlace
}

/** The lab session a day's check-ins belong to. See the table in [CheckInPlace]. */
object CheckInSessionId {

    /**
     * `<site>-<YYYY-MM-DD>` in the device's local date.
     *
     * A blank site gives a blank id, and the caller refuses the check-in rather than inventing one:
     * a scan filed under `-2026-09-18` would aggregate every deployment in the world into one tally.
     */
    fun forDay(site: String, wallMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (site.isBlank()) return ""
        val date = Instant.fromEpochMilliseconds(wallMillis).toLocalDateTime(zone).date
        val month = date.monthNumber.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        return "$site-${date.year}-$month-$day"
    }
}

/** The thresholds a check-in is governed by. Constants, so a test can state them. */
object CheckInPolicy {

    /**
     * The ceiling on one check-in. Three hours.
     *
     * A forgotten check-in is worse than a missing one: it reports a person in a room they left,
     * for as long as the phone has battery, and the occupancy series is a cumulative sum so the
     * error never washes out. Three hours is longer than the longest quest on the board (the study
     * session, 240 min, is the one exception and it drives its own session) and short enough that
     * a check-in left running at the end of a lecture closes before the evening.
     *
     * The auto-close writes an ordinary `out` row. It is marked as automatic in the app's own state
     * so the participant is told, but on the wire it is what it is: this person is no longer here.
     * Nothing in this file ever manufactures an `in` a human did not scan.
     */
    const val MAX_DURATION_MILLIS: Long = 3 * 60 * 60 * 1000L

    /**
     * How often the elapsed time is recomputed, for the card and the OS indicator. One second.
     *
     * A displayed timer, not a measurement: every stamp that reaches storage is taken once, at the
     * instant of the scan, by [GroundTruthRecorder]. This tick only redraws.
     */
    const val TICK_MILLIS: Long = 1_000L
}

/** What the app is doing about presence right now. */
sealed interface CheckInState {

    /** Nobody is checked in on this handset. */
    data object Idle : CheckInState

    /**
     * A check-in is running.
     *
     * [startedWallMillis] is what the elapsed time is measured from for DISPLAY. The record that
     * reaches the server carries both clocks, taken together at the scan — see [GroundTruthEvent].
     */
    data class Active(
        val place: CheckInPlace,
        val labSessionId: String,
        val startedWallMillis: Long,
        val startedMonotonicNanos: Long,
        /**
         * The pseudonym this check-in was opened under.
         *
         * Carried on the state rather than looked up again at check-out, so the `out` row is
         * filed against the same participant as the `in`. Re-reading the account at close time
         * would put the exit on a different pseudonym if somebody signed out mid-visit, and the
         * occupancy sum would then never come back down.
         */
        val participantToken: String,
        /** What the radio accepted, when the deployment broadcasts at all. Null when it does not. */
        val broadcast: BroadcastReport? = null,
        /** Filled once the ceiling closes a check-in, so the card can say why it ended. */
        val autoClosed: Boolean = false,
    ) : CheckInState {

        fun elapsedMillis(nowWallMillis: Long): Long =
            (nowWallMillis - startedWallMillis).coerceAtLeast(0L)

        fun isExpired(nowWallMillis: Long): Boolean =
            elapsedMillis(nowWallMillis) >= CheckInPolicy.MAX_DURATION_MILLIS
    }
}

/** Why a check-in ended. Recorded in the app's state; the wire row is an ordinary `out` either way. */
enum class CheckOutReason {
    /** The participant scanned the same card again. */
    SCANNED,

    /** The participant pressed Check out, in the app or on the OS indicator. */
    TAPPED,

    /** The participant scanned a different card, so this place was left. */
    MOVED,

    /** [CheckInPolicy.MAX_DURATION_MILLIS] elapsed. */
    TIMED_OUT,
}

/** What a scan did, in words the card and the indicator can both use. */
sealed interface CheckInOutcome {

    data class CheckedIn(val place: CheckInPlace) : CheckInOutcome

    data class CheckedOut(val place: CheckInPlace, val reason: CheckOutReason, val durationMillis: Long) :
        CheckInOutcome

    data class Moved(val from: CheckInPlace, val to: CheckInPlace) : CheckInOutcome

    /**
     * The scan was read but is not a card of ours — a shop receipt, a Wi-Fi config, someone else's
     * QR. Distinguished from a failure because the participant has done nothing wrong and the
     * sentence they need is different.
     */
    data object NotOurCard : CheckInOutcome

    /** Something refused. [reason] is already a sentence a participant can act on. */
    data class Failed(val reason: String) : CheckInOutcome
}
