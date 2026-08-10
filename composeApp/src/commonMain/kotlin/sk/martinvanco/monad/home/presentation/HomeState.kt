package sk.martinvanco.monad.home.presentation

import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.home.presentation.model.QuestCardDt
import sk.martinvanco.monad.lab.domain.ZoneState
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.health.StreamState

data class HomeState(
    val isScanning: Boolean = false,
    val advertisements: List<BleAdvertisement> = emptyList(),
    val filterText: String = "",
    val filteredAdvertisements: List<BleAdvertisement> = emptyList(),
    val quests: List<QuestCardDt> = emptyList(),
    val isLoadingQuests: Boolean = false,
    val questsError: String? = null,
    val beaconCount: Long = 0,
    val isInstrumentRunning: Boolean = false,
    val unsyncedSessions: Long = 0,
    val isUploading: Boolean = false,
    val uploadSuccess: Boolean? = null,
    val uploadError: String? = null,
    val userName: String? = null,
    /** Live per-stream liveness, so the first screen can say more than "running". */
    val health: InstrumentHealth = InstrumentHealth.IDLE,
    /** Which zone this participant scanned into, by their own scans. */
    val zone: ZoneState = ZoneState(),
    /** Sessions rescued from a crash or reboot on this launch. */
    val recoveredSessions: Int = 0,
) {
    /**
     * The one sentence the home screen owes a participant who just unlocked their phone.
     *
     * "Recording" on its own would be a lie the moment a stream quietly stops, which is the failure
     * this whole surface exists to catch — so a dead stream changes the headline, not a footnote.
     */
    val instrumentHeadline: String
        get() = when {
            !isInstrumentRunning -> "Not recording"
            health.overall == StreamState.DEAD -> "Recording — something has stopped"
            health.overall == StreamState.STALE || health.overall == StreamState.DEGRADED ->
                "Recording — one stream is struggling"

            else -> "Recording"
        }

    val instrumentIsNominal: Boolean get() = isInstrumentRunning && health.overall.isHealthy

    /** Age of the freshest event across every stream, in milliseconds, or null if nothing yet. */
    val lastEventAgeMillis: Long?
        get() = health.streams.filter { it.everProduced }.minOfOrNull { it.silenceMillis }
}
