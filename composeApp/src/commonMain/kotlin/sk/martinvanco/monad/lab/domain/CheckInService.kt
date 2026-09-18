package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sk.martinvanco.monad.core.domain.marker.MarkerCode
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * Scan a card, and the phone starts counting the time you spent there.
 *
 * One object owns the whole of it, because the four things a check-in does have to happen together
 * or not at all: write the ground-truth row, put the identity frame on air, raise the OS indicator,
 * and start the ceiling that closes a forgotten one. Split across screens they drift, and the
 * failure is silent — a broadcast with no row behind it, or a row with an indicator that outlives
 * it.
 *
 * ## The order, and why it is this order
 *
 * 1. **Fold the scan.** Not one of our cards, and nothing else happens. A participant who pointed
 *    the camera at a shop receipt gets a sentence, not a check-in at `receipt-4471`.
 * 2. **Write the row.** First, and before anything visible. It is the only part that is a
 *    measurement, and everything after it is decoration that must not be able to prevent it.
 * 3. **Broadcast, if the deployment broadcasts at all.** Best effort: a refusal (Bluetooth off, no
 *    advertise permission, no namespace in the bundle) is reported in the state and never fails the
 *    check-in. The person is still in the room.
 * 4. **Raise the indicator.** Also best effort, for the same reason.
 * 5. **Arm the ceiling.**
 *
 * ## What it never does
 *
 * It never manufactures an `in`. Every `in` row here comes from a human pointing a camera at a
 * printed card. The automatic close writes an `out`, which is a statement that somebody is no
 * longer somewhere — the safe direction, and the one whose absence corrupts a cumulative sum.
 */
class CheckInService(
    private val recorder: GroundTruthRecorder,
    private val places: PlaceDirectory,
    private val broadcaster: IdentityBroadcaster,
    private val indicator: PresenceIndicator,
    private val site: LabSiteSource,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<CheckInState>(CheckInState.Idle)
    val state: StateFlow<CheckInState> = _state.asStateFlow()

    /** Redrawn once a second so the in-app card ticks. Nothing here is stamped from it. */
    private val _now = MutableStateFlow(currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private var ticker: Job? = null

    init {
        // The OS indicator's Check out button arrives here, from an Android BroadcastReceiver or an
        // iOS URL open. Collected for the life of the service rather than per screen: the tap
        // usually happens with the app in the background and no screen collecting anything.
        scope.launch {
            PresenceCommands.commands.collect { command ->
                when (command) {
                    PresenceCommand.CHECK_OUT -> checkOut(CheckOutReason.TAPPED)
                }
            }
        }
    }

    /**
     * A scan happened. Works out what it means and does it.
     *
     * Three cases, decided by what is already running: nothing (check in), the same card (check
     * out), a different card (move). The participant is never asked which one they meant — they
     * scanned a card, and the app already knows where they were.
     */
    suspend fun onScan(raw: String, participantToken: String): CheckInOutcome {
        val key = MarkerCode.key(raw)
        if (key.isEmpty()) return CheckInOutcome.NotOurCard

        val active = _state.value as? CheckInState.Active
        return when {
            active == null -> checkIn(key, participantToken)
            active.place.key == key -> checkOut(CheckOutReason.SCANNED)
            else -> move(active, key, participantToken)
        }
    }

    /** Close the running check-in, if there is one. Safe to call when there is not. */
    suspend fun checkOut(reason: CheckOutReason): CheckInOutcome {
        val active = _state.value as? CheckInState.Active ?: return CheckInOutcome.Failed(
            "You are not checked in anywhere."
        )
        val duration = active.elapsedMillis(currentTimeMillis())
        writeRow(active.place, active.labSessionId, GroundTruthDirection.OUT, active.participantToken)
        teardown()
        Napier.i("[check-in] out of ${active.place.key} after ${duration / 1000} s ($reason)")
        return CheckInOutcome.CheckedOut(active.place, reason, duration)
    }

    /** What the lab console prints about this path. */
    fun diagnostics(): List<String> = buildList {
        add("session id: ${CheckInSessionId.forDay(site.siteSlug(), currentTimeMillis()).ifBlank { "UNSET — no site in the lab bundle, check-in refuses" }}")
        addAll(indicator.diagnostics())
    }

    private suspend fun checkIn(key: String, participantToken: String): CheckInOutcome {
        val siteSlug = site.siteSlug()
        val labSessionId = CheckInSessionId.forDay(siteSlug, currentTimeMillis())
        if (labSessionId.isBlank()) {
            // Refused rather than defaulted. A scan filed under a blank site would aggregate every
            // deployment into one tally, and nothing downstream could tell the rows apart again.
            return CheckInOutcome.Failed(
                "This phone has not been told which site it is at yet. Open the app while online " +
                    "once, then try again."
            )
        }

        val place = places.resolve(key)
        val written = writeRow(place, labSessionId, GroundTruthDirection.IN, participantToken)
        if (written != null) return CheckInOutcome.Failed(written)

        val startedWall = currentTimeMillis()
        val active = CheckInState.Active(
            place = place,
            labSessionId = labSessionId,
            startedWallMillis = startedWall,
            startedMonotonicNanos = monotonicNanos(),
            participantToken = participantToken,
        )
        _state.value = active

        _state.value = active.copy(broadcast = startBroadcast(place, labSessionId, participantToken))
        raiseIndicator(place, startedWall)
        startTicker()

        Napier.i("[check-in] in at ${place.key} (${place.display}) session=$labSessionId")
        return CheckInOutcome.CheckedIn(place)
    }

    /**
     * A different card. One `out` and one `in`, both stamped now, and one indicator that follows.
     *
     * The exit is written even though nobody scanned an exit code, for the reason
     * [GroundTruthRecorder] writes an implied exit: occupancy is a cumulative sum per place, and a
     * participant who moves without checking out leaves the old place one person too high for the
     * rest of the day, invisibly.
     */
    private suspend fun move(
        active: CheckInState.Active,
        key: String,
        participantToken: String,
    ): CheckInOutcome {
        writeRow(active.place, active.labSessionId, GroundTruthDirection.OUT, participantToken)
        val place = places.resolve(key)
        val written = writeRow(place, active.labSessionId, GroundTruthDirection.IN, participantToken)
        if (written != null) {
            // The exit landed and the entry did not. Better to be checked out of somewhere real
            // than checked into somewhere that has no row: teardown, and say so.
            teardown()
            return CheckInOutcome.Failed(written)
        }

        val startedWall = currentTimeMillis()
        _state.value = active.copy(
            place = place,
            startedWallMillis = startedWall,
            startedMonotonicNanos = monotonicNanos(),
        )
        indicator.update(PresenceSnapshot(place.display, startedWall, startedWall + CheckInPolicy.MAX_DURATION_MILLIS))
        Napier.i("[check-in] moved ${active.place.key} -> ${place.key}")
        return CheckInOutcome.Moved(from = active.place, to = place)
    }

    /** Returns null on success, or the sentence to show. */
    private suspend fun writeRow(
        place: CheckInPlace,
        labSessionId: String,
        direction: GroundTruthDirection,
        participantToken: String,
    ): String? {
        val ticket = GroundTruthTicket(
            labSessionId = labSessionId,
            zoneId = place.key,
            site = site.siteSlug(),
            declaredDirection = direction,
        )
        return recorder.record(ticket, participantToken).fold(
            onSuccess = { null },
            onFailure = {
                Napier.e("[check-in] row NOT written: ${it.message}", it)
                "That was not recorded — ${it.message ?: "the phone could not write it"}. " +
                    "Try scanning again, or tell the operator."
            },
        )
    }

    /**
     * Put the identity frame on air for the length of the visit, when the deployment has one.
     *
     * Best effort by design. An empty `advertise.namespace_uuid` means this deployment does not
     * broadcast, which is a configuration fact and not a fault; Bluetooth being off is the
     * participant's business. Neither is allowed to cost a person in the room.
     */
    private suspend fun startBroadcast(
        place: CheckInPlace,
        labSessionId: String,
        participantToken: String,
    ): BroadcastReport? {
        val plan = site.advertisePlan()
        if (!plan.isConfigured) return null
        val uuid = AdvertiseIdentity.serviceUuid(
            namespaceUuid = plan.namespaceUuid,
            participantId = participantToken,
            sessionId = "$labSessionId/${place.key}",
        ) ?: return null
        return broadcaster.start(
            BroadcastRequest(serviceUuid = uuid, intervalMs = plan.intervalMs, txPower = plan.txPower)
        ).onFailure { Napier.i("[check-in] not broadcasting: ${it.message}") }.getOrNull()
    }

    private suspend fun raiseIndicator(place: CheckInPlace, startedWall: Long) {
        indicator.start(
            PresenceSnapshot(
                place = place.display,
                startedWallMillis = startedWall,
                endsAtWallMillis = startedWall + CheckInPolicy.MAX_DURATION_MILLIS,
            )
        )
    }

    /**
     * The clock that redraws the card, and the ceiling.
     *
     * One coroutine for both, because they answer the same question once a second and a second
     * timer would be a second thing to cancel. It is a display clock: every stamp that reaches
     * storage was taken at the scan.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val now = currentTimeMillis()
                _now.value = now
                val active = _state.value as? CheckInState.Active ?: break
                if (active.isExpired(now)) {
                    Napier.i("[check-in] ceiling reached at ${active.place.key}, closing")
                    _state.value = active.copy(autoClosed = true)
                    checkOut(CheckOutReason.TIMED_OUT)
                    break
                }
                delay(CheckInPolicy.TICK_MILLIS)
            }
        }
    }

    private suspend fun teardown() {
        ticker?.cancel()
        ticker = null
        broadcaster.stop()
        indicator.stop()
        _state.value = CheckInState.Idle
    }
}

/**
 * The two bundle facts a check-in needs: where this phone is, and whether it may broadcast.
 *
 * A port because `lab/domain` may not name `lab/data` (`LabBoundaryTest`), and because the two
 * answers come from a cached HTTP response whose freshness is somebody else's problem. Blank site
 * is a real answer: it means this handset has never successfully fetched a bundle, and the check-in
 * refuses rather than filing rows under a name nothing can resolve.
 */
interface LabSiteSource {

    /** The PostGIS site slug, e.g. `fiit-ground-0`. Blank when no bundle has been fetched. */
    fun siteSlug(): String

    /** The identity-broadcast plan. `isConfigured == false` means this deployment does not emit. */
    fun advertisePlan(): AdvertisePlan
}
