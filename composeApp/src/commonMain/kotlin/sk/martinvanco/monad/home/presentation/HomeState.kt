package sk.martinvanco.monad.home.presentation

import sk.martinvanco.monad.ble.domain.BleAdvertisement

data class HomeState(
    val isScanning: Boolean = false,
    val advertisements: List<BleAdvertisement> = emptyList(),
    val filterText: String = "",
    val filteredAdvertisements: List<BleAdvertisement> = emptyList()
)
