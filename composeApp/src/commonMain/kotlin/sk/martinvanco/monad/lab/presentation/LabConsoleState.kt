package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.InstrumentLogLine
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabInstrumentState
import sk.martinvanco.monad.lab.domain.ResidencyCheck
import sk.martinvanco.monad.lab.domain.TrafficStats

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
) {
    val isRunning: Boolean get() = instrument.isRunning
    val residencyBlockers: List<ResidencyCheck> get() = residency.filterNot { it.satisfied }
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
}
