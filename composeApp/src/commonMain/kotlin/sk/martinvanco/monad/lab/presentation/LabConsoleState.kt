package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.data.GroundTruthTally
import sk.martinvanco.monad.lab.data.GroundTruthZoneTally
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.domain.BlockKind
import sk.martinvanco.monad.lab.domain.BlockSessionState
import sk.martinvanco.monad.lab.domain.BlockTally
import sk.martinvanco.monad.lab.domain.BlockWarning
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.InstrumentLogLine
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabInstrumentState
import sk.martinvanco.monad.lab.domain.LabStaircase
import sk.martinvanco.monad.lab.domain.LabZones
import sk.martinvanco.monad.lab.domain.ResidencyCheck
import sk.martinvanco.monad.lab.domain.SubCondition
import sk.martinvanco.monad.lab.domain.TallySource
import sk.martinvanco.monad.lab.domain.TrafficStats
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.preflight.PreflightReport
import sk.martinvanco.monad.lab.domain.upload.FlushReport

/**
 * State of the lab console — the operator-facing surface of the instrument.
 *
 * The console is a first-class deliverable, not a debug afterthought. Every failure mode this
 * instrument has is silent by nature: an unpinned socket still "sends", a revoked authorization
 * still leaves the app running in the foreground, a throttled background process still reports a
 * commanded rate. The console exists so those become visible on a bench in seconds instead of
 * being discovered after a field session has produced unusable data.
 */
data class LabConsoleState(
    val config: LabConfig = LabConfig.EMPTY,
    val configSource: LabConfigService.Source = LabConfigService.Source.NONE,
    val instrument: LabInstrumentState = LabInstrumentState.IDLE,
    val traffic: TrafficStats = TrafficStats.IDLE,
    val clock: ClockEstimate = ClockEstimate.UNSYNCED,
    val residency: List<ResidencyCheck> = emptyList(),
    val witnessDiagnostics: List<String> = emptyList(),
    val log: List<InstrumentLogLine> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val unsyncedCount: Long = 0,
    val selectedApId: String? = null,
    val selectedProfileId: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    // Manual overrides, so a bench rig works before the backend knows it exists.
    val manualHost: String = "",
    val manualPort: String = "9999",
    val manualBeaconUuid: String = "",
    // Ground truth — the people channel. See GroundTruthEvent.
    val groundTruthZoneId: String = "",
    /**
     * The encoded check-in code, or null when there is no session to anchor it to.
     *
     * Deliberately null rather than a placeholder: a code that names no session would produce
     * scans that cannot be joined to any measurement, and printing one is worse than printing none.
     */
    val groundTruthQrPayload: String? = null,
    /** Participant tokens whose last scan **on this device** was an entry. */
    val groundTruthCheckedIn: List<String> = emptyList(),
    val groundTruthPending: Long = 0,
    /**
     * Last tally the backend actually returned, or null if it has never answered this session.
     *
     * Kept across failed polls on purpose: a number that is thirty seconds old and labelled as such
     * is useful, and blanking it on the first timeout would throw away the only room-wide figure
     * the operator has. Age is what makes it safe, so [roomTallyAtMillis] travels with it.
     */
    val roomTally: GroundTruthTally? = null,
    /** Device wall clock at the last **successful** poll. */
    val roomTallyAtMillis: Long? = null,
    /** Device wall clock at the last poll **attempt**, so the displayed age keeps growing. */
    val roomTallyNowMillis: Long = 0,
    val roomTallyError: String? = null,
    /**
     * Per-stream liveness and the clock gate, straight off the instrument's heartbeat.
     *
     * On the console — as opposed to the participant status screen — this exists mostly for its
     * clock block: G4b's 250 ms budget is what block boundaries are held to, and a phone drifting
     * toward it has to be visible while it can still be fixed.
     */
    val health: InstrumentHealth = InstrumentHealth.IDLE,
    // ---- block control -----------------------------------------------------------------
    val blocks: BlockSessionState = BlockSessionState.EMPTY,
    val blockZoneId: String = "",
    val blockLevel: Int = 0,
    val blockSubCondition: SubCondition = SubCondition.SEATED,
    val blockKind: BlockKind = BlockKind.STAIRCASE,
    /** Monotonic nanoseconds as of the last display tick — drives the running block's elapsed time. */
    val blockNowMonotonicNanos: Long = 0,
    /** Live guard-rail findings for the running block (over-budget), recomputed each tick. */
    val blockLiveWarnings: List<BlockWarning> = emptyList(),
    /** Warnings raised at the last block edge, kept on screen until the next one. */
    val blockLastWarnings: List<BlockWarning> = emptyList(),
    // ---- pre-flight --------------------------------------------------------------------
    val preflight: PreflightReport? = null,
    val preflightRunning: Boolean = false,
    /**
     * The last flush, so an E3 conflict the *upload* path saw is visible on the console.
     *
     * The room-tally poll shows conflicts the server already knows about; this shows the ones this
     * phone just discovered by pushing scans. They are different moments, and the second one is
     * where a conflict is first knowable.
     */
    val lastFlush: FlushReport? = null,
) {
    val isRunning: Boolean get() = instrument.isRunning
    val residencyBlockers: List<ResidencyCheck> get() = residency.filterNot { it.satisfied }

    /** Checked in according to **this handset only**. With N participants this is 0 or 1. */
    val groundTruthCount: Int get() = groundTruthCheckedIn.size

    /** Seconds since the backend last answered, or null if it never has. */
    val roomTallyAgeSeconds: Long?
        get() = roomTallyAtMillis?.let { ((roomTallyNowMillis - it).coerceAtLeast(0)) / 1000 }

    /**
     * Which number the ground-truth panel is showing.
     *
     * This is the state's job, not the view's. The one thing an operator must never have to infer
     * is whether the count in front of them is the room or the phone in their hand — those differ
     * by a factor of ten in this session, and a wrong guess silently corrupts the staircase.
     */
    val roomTallySource: TallySource
        get() = when {
            roomTally == null -> TallySource.DEVICE_ONLY
            (roomTallyAgeSeconds ?: Long.MAX_VALUE) > STALE_AFTER_SECONDS -> TallySource.ROOM_STALE
            else -> TallySource.ROOM_LIVE
        }

    /** Room-wide count when there is one, otherwise this device's. Read with [roomTallySource]. */
    val displayedGroundTruthCount: Int
        get() = roomTally?.overall?.checkedIn ?: groundTruthCount

    /** Zones whose latest-wins count disagrees with the cumulative sum — a double-scan tell. */
    val roomTallyInconsistentZones: List<GroundTruthZoneTally>
        get() = roomTally?.zones.orEmpty().filter { it.isInconsistent }

    val roomTallyConflictCount: Int get() = roomTally?.overall?.conflictCount ?: 0

    /** Conflicts the last flush's ingest receipt reported. E3, seen at the moment it happened. */
    val flushConflictCount: Int get() = lastFlush?.tally?.conflicts ?: 0

    /**
     * The tally as the block guard-rails see it — the number *and* where it came from.
     *
     * Null when there is nothing at all, which the guards report as "no room-wide tally" rather
     * than as a disagreement. A device-only count of 1 against a declared level of 4 is not an
     * error, and a guard-rail that cried wolf on it would be switched off by lunchtime.
     */
    val blockTally: BlockTally?
        get() = when {
            roomTally != null -> BlockTally(displayedGroundTruthCount, roomTallySource)
            groundTruthCheckedIn.isNotEmpty() -> BlockTally(groundTruthCount, TallySource.DEVICE_ONLY)
            else -> null
        }

    /** Zones the block panel offers: the bundle's own vocabulary, or the frozen triple on a bench. */
    val blockZoneOptions: List<String>
        get() = config.beacons.zones.map { it.cellId }.filter { it.isNotBlank() }
            .ifEmpty { LabZones.DEFAULT }

    val blockLevelOptions: List<Int> get() = LabStaircase.LEVELS

    /** Elapsed time of the running block, in milliseconds, as of the last tick. */
    val blockElapsedMillis: Long
        get() = blocks.active?.elapsedMillis(blockNowMonotonicNanos) ?: 0L

    /** 0..1 against the running block's ceiling, or null when the kind is untimed. */
    val blockBudgetFraction: Float?
        get() {
            val max = blocks.active?.kind?.maxSeconds ?: return null
            return (blockElapsedMillis.toFloat() / (max * 1000f)).coerceIn(0f, 1f)
        }

    companion object {
        /**
         * Past this, the room number is labelled stale rather than shown as current.
         *
         * Three missed polls at the four-second cadence. Long enough that one dropped request on a
         * congested experiment AP does not flap the label, short enough that it cannot silently
         * lag a staircase level — the shortest plateau in the plan is 1.5 min.
         */
        const val STALE_AFTER_SECONDS: Long = 15
    }
}

data class SessionRow(
    val sessionId: String,
    val status: String,
    val startedWallMillis: Long,
    val participantId: String,
    val socketPinned: Boolean,
    val boundInterface: String,
    val uploadError: String?,
)

sealed interface LabConsoleEvent {
    data object RefreshConfig : LabConsoleEvent
    data object StartSession : LabConsoleEvent
    data object StopSession : LabConsoleEvent
    data object RunClockBurst : LabConsoleEvent
    data object RequestPrerequisites : LabConsoleEvent
    data object RetryUploads : LabConsoleEvent
    data object ClearLog : LabConsoleEvent
    data object ApplyManualCollector : LabConsoleEvent
    data class SelectAp(val apId: String) : LabConsoleEvent
    data class SelectProfile(val profileId: String) : LabConsoleEvent
    data class UpdateManualHost(val value: String) : LabConsoleEvent
    data class UpdateManualPort(val value: String) : LabConsoleEvent
    data class UpdateManualBeaconUuid(val value: String) : LabConsoleEvent
    data class DeleteSession(val sessionId: String) : LabConsoleEvent
    data class SelectGroundTruthZone(val zoneId: String) : LabConsoleEvent
    data object RefreshGroundTruth : LabConsoleEvent
    data object FlushGroundTruth : LabConsoleEvent

    /** Poll the server-side room tally now, rather than waiting for the next tick. */
    data object RefreshRoomTally : LabConsoleEvent

    // ---- block control -----------------------------------------------------------------
    data class SelectBlockZone(val zoneId: String) : LabConsoleEvent
    data class SelectBlockLevel(val level: Int) : LabConsoleEvent
    data class SelectBlockSubCondition(val subCondition: SubCondition) : LabConsoleEvent
    data class SelectBlockKind(val kind: BlockKind) : LabConsoleEvent
    data object StartBlock : LabConsoleEvent
    data object StopBlock : LabConsoleEvent

    // ---- pre-flight --------------------------------------------------------------------
    data object RunPreflight : LabConsoleEvent
}
