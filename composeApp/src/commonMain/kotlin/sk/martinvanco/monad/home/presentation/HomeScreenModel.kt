package sk.martinvanco.monad.home.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.ble.domain.BleScanner

class HomeScreenModel(
    private val bleScanner: BleScanner
) : StateScreenModel<HomeState>(HomeState()) {

    private var scanJob: Job? = null

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.StartBleScan -> startScanning()
            is HomeEvent.StopBleScan -> stopScanning()
            is HomeEvent.UpdateFilter -> updateFilter(event.filterText)
            is HomeEvent.OpenQuestDetailScreen -> {
                // TODO: Navigate to quest detail screen
            }
        }
    }

    private fun updateFilter(filterText: String) {
        mutableState.value = mutableState.value.copy(
            filterText = filterText,
            filteredAdvertisements = filterAdvertisements(
                mutableState.value.advertisements,
                filterText
            )
        )
    }

    private fun filterAdvertisements(
        advertisements: List<BleAdvertisement>,
        filterText: String
    ): List<BleAdvertisement> {
        if (filterText.isBlank()) {
            return advertisements
        }

        val filter = filterText.lowercase().trim()
        return advertisements.filter { ad ->
            // Filter by name
            ad.name?.lowercase()?.contains(filter) == true ||
            // Filter by address/identifier
            ad.address.lowercase().contains(filter) ||
            // Filter by service UUIDs
            ad.serviceUuids?.any { uuid ->
                uuid.lowercase().contains(filter)
            } == true
        }
    }

    private fun startScanning() {
        if (mutableState.value.isScanning) return

        screenModelScope.launch {
            bleScanner.startScanning()
        }

        // Listen to scanning state
        bleScanner.isScanning
            .onEach { isScanning ->
                mutableState.value = mutableState.value.copy(isScanning = isScanning)
            }
            .launchIn(screenModelScope)

        // Collect advertisements
        scanJob = bleScanner.advertisements
            .onEach { advertisement ->
                val currentAds = mutableState.value.advertisements.toMutableList()

                // Update or add the advertisement
                val existingIndex = currentAds.indexOfFirst { it.address == advertisement.address }
                if (existingIndex >= 0) {
                    currentAds[existingIndex] = advertisement
                } else {
                    currentAds.add(advertisement)
                }

                mutableState.value = mutableState.value.copy(
                    advertisements = currentAds,
                    filteredAdvertisements = filterAdvertisements(
                        currentAds,
                        mutableState.value.filterText
                    )
                )
            }
            .launchIn(screenModelScope)
    }

    private fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        bleScanner.stopScanning()
        mutableState.value = mutableState.value.copy(isScanning = false)
    }

    override fun onDispose() {
        super.onDispose()
        stopScanning()
    }
}
