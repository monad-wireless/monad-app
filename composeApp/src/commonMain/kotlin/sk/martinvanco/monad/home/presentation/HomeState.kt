package sk.martinvanco.monad.home.presentation

import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.home.domain.model.QuestCardDt

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
    val userName: String? = null
)
