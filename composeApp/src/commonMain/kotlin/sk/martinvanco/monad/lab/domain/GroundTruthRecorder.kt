package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import sk.martinvanco.monad.core.util.currentTimeMillis
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Turns a scanned code into stamped ground-truth events.
 *
 * The science-relevant behaviour lives here, and it is short on purpose:
 *
 * 1. **The stamp is taken here, not at upload.** A scan that waits four hours in a pocket for a
 *    network must still say when it happened. Both clocks are taken in the same breath —
 *    [monotonicNanos] because it is the column every other stream joins on and the only one that
 *    survives a backgrounded session without compressing, [currentTimeMillis] because a person
 *    walking through a door is a wallclock event and the operator's notebook says 14:03.
 * 2. **Toggle is resolved against this participant's own history**, so one code per doorway works
 *    and a participant's state is never affected by anybody else's scan.
 * 3. **Entering a zone leaves the previous one.** The August session runs three zones inside one
 *    hall and derives each zone's occupancy as a cumulative sum of `direction`. A participant who
 *    walks A → B and scans only B's code would leave A's count one person too high for the rest of
 *    the session, invisibly. So an implicit `out` is written for the zone being left. It is a real
 *    event — the person did leave — and it is written as an ordinary row with its own nonce.
 *    Nothing here ever manufactures an `in` that a human did not scan.
 * 4. **The instrument is read, never touched.** This class asks the running session for its id and
 *    tells it a scan happened; it cannot stop an emission, drop a beacon subscription, or perturb
 *    the radio. The illuminator and witness paths are the measurement — ground truth rides alongside.
 */
@OptIn(ExperimentalUuidApi::class)
class GroundTruthRecorder(
    private val repository: GroundTruthStore,
    private val instrument: LabInstrument,
) {

    /**
     * Record one scan.
     *
     * Returns everything that happened, with `toggle` already resolved and any implicit exit named,
     * so the UI can tell the participant which way it counted them and which zone they just left.
     * That feedback is what makes a toggle code trustworthy to the person using it.
     */
    suspend fun record(
        ticket: GroundTruthTicket,
        participantToken: String,
    ): Result<ScanReceipt> {
        if (!ticket.isValid) {
            return Result.failure(IllegalArgumentException("code carries no session or zone"))
        }
        if (participantToken.isBlank()) {
            return Result.failure(IllegalStateException("no participant token — sign in first"))
        }

        val history = repository.eventsForParticipant(ticket.labSessionId, participantToken)
        val previousSession = repository.lastScannedSession(participantToken)
        val nowWall = currentTimeMillis()

        return when (
            val decision = ScanPlanner.plan(
                ticket = ticket,
                history = history,
                nowWallMillis = nowWall,
                knownSessions = setOfNotNull(previousSession),
            )
        ) {
            is ScanDecision.Rejected ->
                Result.failure(IllegalArgumentException(decision.reason))

            is ScanDecision.Duplicate ->
                Result.success(
                    ScanReceipt(
                        recorded = emptyList(),
                        primary = decision.previous,
                        duplicateOfAgeMillis = decision.ageMillis,
                    )
                )

            is ScanDecision.Record -> write(ticket, participantToken, decision, nowWall)
        }
    }

    private suspend fun write(
        ticket: GroundTruthTicket,
        participantToken: String,
        decision: ScanDecision.Record,
        nowWallMillis: Long,
    ): Result<ScanReceipt> {
        // One monotonic reading for the whole decision: the implicit exit and the entry describe
        // the same instant, and giving them different stamps would invent an ordering that the
        // person did not perform.
        val mono = monotonicNanos()
        val recordingSessionId = instrument.state.value.sessionId?.takeIf { it.isNotEmpty() }
        val events = decision.rows.map { row ->
            GroundTruthEvent(
                labSessionId = ticket.labSessionId,
                participantToken = participantToken,
                zoneId = row.zoneId,
                direction = row.direction,
                site = ticket.site,
                monotonicNanos = mono,
                wallMillis = nowWallMillis,
                scanNonce = Uuid.random().toString(),
                recordingSessionId = recordingSessionId,
            )
        }

        return runCatching { events.forEach { repository.record(it) } }
            .fold(
                onSuccess = {
                    instrument.noteGroundTruthScan(events.size)
                    val primary = events.last()
                    Napier.i(
                        "[lab] ground truth ${primary.direction.wire} zone=${primary.zoneId} " +
                            "session=${ticket.labSessionId.take(8)}" +
                            (decision.movedFrom?.let { " (left $it)" } ?: "")
                    )
                    Result.success(
                        ScanReceipt(
                            recorded = events,
                            primary = primary,
                            movedFrom = decision.movedFrom,
                            sessionChangedFrom = decision.sessionChangedFrom,
                        )
                    )
                },
                onFailure = {
                    // Loud, because a dropped scan is a person who was in the room and is not in the
                    // data — the one failure mode this channel exists to prevent.
                    Napier.e("[lab] ground truth NOT recorded: ${it.message}", it)
                    Result.failure(it)
                },
            )
    }
}

/**
 * What a scan actually did.
 *
 * [duplicateOfAgeMillis] is non-null when nothing was written because the same code was already
 * scanned moments ago. That is not a failure and must not be shown as one: the participant is owed
 * "you are already checked in, four seconds ago", not an error.
 */
data class ScanReceipt(
    val recorded: List<GroundTruthEvent>,
    val primary: GroundTruthEvent,
    val movedFrom: String? = null,
    val sessionChangedFrom: String? = null,
    val duplicateOfAgeMillis: Long? = null,
) {
    val isDuplicate: Boolean get() = duplicateOfAgeMillis != null
    val impliedExits: List<GroundTruthEvent> get() = recorded.dropLast(1)
}
