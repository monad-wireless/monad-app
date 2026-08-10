package sk.martinvanco.monad.lab.domain

/**
 * Where a participant is, according to their own scans.
 *
 * The August session runs **three** zones (ZONE-A / ZONE-B / ZONE-C) inside one hall, and the whole
 * design leans on the occupancy of each being derivable as a cumulative sum of `direction` over
 * `(lab_session_id, zone_id)` (pre-registration §3.5). That sum is only correct if leaving a zone
 * produces an `out` row. A participant who walks from A to B and scans only B's code leaves A's
 * count permanently one person too high, for the rest of the session, invisibly.
 *
 * So the app takes the position that **entering a zone is leaving the previous one**. The implicit
 * exit is written as an ordinary row with its own nonce, indistinguishable from a scan — because it
 * *is* true: the person really did leave. What is not acceptable is inferring presence; nothing here
 * ever creates an `in` that a human did not scan.
 *
 * Kept pure so the three-zone traversal can be tested without a camera or a database.
 */

/** One zone the participant is currently inside, and since when. */
data class ZonePresence(
    val zoneId: String,
    val sinceMonotonicNanos: Long,
    val sinceWallMillis: Long,
)

/**
 * Resolved presence.
 *
 * [ambiguous] is normally empty. It is non-empty only for history recorded before implicit exits
 * existed, or for codes issued with explicit `dir=in` on two doorways; surfacing it is better than
 * silently picking one.
 */
data class ZoneState(
    val current: ZonePresence? = null,
    val ambiguous: List<ZonePresence> = emptyList(),
    val lastEvent: GroundTruthEvent? = null,
) {
    val isCheckedIn: Boolean get() = current != null
    val zoneLabel: String get() = current?.zoneId ?: "not checked in"
    val isAmbiguous: Boolean get() = ambiguous.isNotEmpty()
}

object ZoneMembership {

    /**
     * Fold a participant's scans for one lab session into a presence.
     *
     * Events are sorted by monotonic nanoseconds because that — never `wall_ms` — is the ordering
     * key the pre-registration declares: the wall clock is subject to NTP steps and user edits
     * mid-session, and re-ordering two scans would flip a participant's zone.
     */
    fun resolve(events: List<GroundTruthEvent>): ZoneState {
        if (events.isEmpty()) return ZoneState()
        val ordered = events.sortedBy { it.monotonicNanos }
        val inside = linkedMapOf<String, ZonePresence>()
        ordered.forEach { event ->
            when (event.direction) {
                GroundTruthDirection.IN -> inside[event.zoneId] = ZonePresence(
                    zoneId = event.zoneId,
                    sinceMonotonicNanos = event.monotonicNanos,
                    sinceWallMillis = event.wallMillis,
                )

                GroundTruthDirection.OUT -> inside.remove(event.zoneId)
            }
        }
        // Most recent entry wins; anything else still open is reported rather than hidden.
        val ranked = inside.values.sortedByDescending { it.sinceMonotonicNanos }
        return ZoneState(
            current = ranked.firstOrNull(),
            ambiguous = ranked.drop(1),
            lastEvent = ordered.last(),
        )
    }

    /** This participant's last recorded direction for one zone — the input to toggle resolution. */
    fun lastDirection(events: List<GroundTruthEvent>, zoneId: String): GroundTruthDirection? =
        events.filter { it.zoneId == zoneId }
            .maxByOrNull { it.monotonicNanos }
            ?.direction
}

/** One row the planner decided to write. */
data class PlannedScan(
    val zoneId: String,
    val direction: GroundTruthDirection,
    /** True when the participant did not scan this — it is the exit implied by entering elsewhere. */
    val implied: Boolean = false,
)

/**
 * The decision a scan produces, before anything is written.
 *
 * A sealed result rather than an exception: "you scanned the same code twice" is not an error, it
 * is a thing that happens in a doorway, and the participant needs to be told what the app did about
 * it rather than shown a failure.
 */
sealed interface ScanDecision {

    /** Write these rows, in order. [movedFrom] is non-null when the participant changed zone. */
    data class Record(
        val rows: List<PlannedScan>,
        val movedFrom: String? = null,
        val sessionChangedFrom: String? = null,
    ) : ScanDecision {
        val primary: PlannedScan get() = rows.last()
    }

    /** The same code, again, within the debounce window. Nothing is written. */
    data class Duplicate(
        val previous: GroundTruthEvent,
        val ageMillis: Long,
    ) : ScanDecision

    /** The code does not carry a usable session or zone. */
    data class Rejected(val reason: String) : ScanDecision
}

/**
 * Turns a scanned ticket plus this participant's history into the rows to write.
 *
 * Pure and total: no clock, no repository, every input explicit.
 */
object ScanPlanner {

    /**
     * How long after an accepted scan an identical one is treated as the scanner firing twice
     * rather than as a person crossing a threshold twice.
     *
     * Eight seconds is chosen against the human act, not the camera: the QRKit scanner is already
     * closed on the first accept, so the realistic double is a participant unsure whether it worked
     * and tapping again. Nobody walks in and back out of a zone in under eight seconds, and if they
     * did, the toggle would still be recoverable by scanning once more.
     */
    const val DUPLICATE_WINDOW_MILLIS: Long = 8_000

    /**
     * @param history this participant's events for the ticket's lab session, any order.
     * @param knownSessions lab session ids this device has scanned before — used only to tell the
     *   participant that the code names a different session than the one they have been in, which
     *   is the "wrong session" case an operator worries about.
     */
    fun plan(
        ticket: GroundTruthTicket,
        history: List<GroundTruthEvent>,
        nowWallMillis: Long,
        duplicateWindowMillis: Long = DUPLICATE_WINDOW_MILLIS,
        knownSessions: Set<String> = emptySet(),
    ): ScanDecision {
        if (!ticket.isValid) return ScanDecision.Rejected("the code carries no session or zone")

        val forZone = history.filter { it.zoneId == ticket.zoneId }
        val lastForZone = forZone.maxByOrNull { it.monotonicNanos }
        if (lastForZone != null) {
            val age = nowWallMillis - lastForZone.wallMillis
            if (age in 0 until duplicateWindowMillis) {
                return ScanDecision.Duplicate(lastForZone, age)
            }
        }

        val direction = ticket.declaredDirection
            ?: lastForZone?.direction?.opposite
            ?: GroundTruthDirection.IN

        val state = ZoneMembership.resolve(history)
        val rows = mutableListOf<PlannedScan>()
        var movedFrom: String? = null

        if (direction == GroundTruthDirection.IN) {
            // Everything else this participant is still inside is left behind, most recent first so
            // the row order matches the order the zones were entered.
            val leaving = buildList {
                state.current?.let { add(it) }
                addAll(state.ambiguous)
            }.filter { it.zoneId != ticket.zoneId }
            leaving.forEach { rows += PlannedScan(it.zoneId, GroundTruthDirection.OUT, implied = true) }
            movedFrom = leaving.firstOrNull()?.zoneId
        }
        rows += PlannedScan(ticket.zoneId, direction)

        val previousSession = knownSessions.firstOrNull { it != ticket.labSessionId }
        return ScanDecision.Record(
            rows = rows,
            movedFrom = movedFrom,
            sessionChangedFrom = previousSession.takeIf {
                knownSessions.isNotEmpty() && ticket.labSessionId !in knownSessions
            },
        )
    }
}
