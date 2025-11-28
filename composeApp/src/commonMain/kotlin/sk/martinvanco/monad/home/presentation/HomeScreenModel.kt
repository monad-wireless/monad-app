package sk.martinvanco.monad.home.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.core.domain.bluetooth.BluetoothStateChecker
import sk.martinvanco.monad.core.domain.toast.ToastManager
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.home.domain.model.QuestCardDt

class HomeScreenModel(
    private val bleScanner: BleScanner,
    private val bluetoothStateChecker: BluetoothStateChecker,
    private val toastManager: ToastManager,
    private val questsService: QuestsService
) : StateScreenModel<HomeState>(HomeState()) {

    private var scanJob: Job? = null

    init {
        loadQuests()
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.StartBleScan -> startScanning()
            is HomeEvent.StopBleScan -> stopScanning()
            is HomeEvent.UpdateFilter -> updateFilter(event.filterText)
            is HomeEvent.OpenQuestDetailScreen -> {
                // Navigation handled in UI
            }
            is HomeEvent.LoadQuests -> loadQuests()
        }
    }

    private fun loadQuests() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isLoadingQuests = true,
                questsError = null
            )
            try {
                val response = questsService.getActiveQuests()
                val quests = response.quests.map { QuestCardDt.fromDto(it) }
                mutableState.value = mutableState.value.copy(
                    quests = quests,
                    isLoadingQuests = false,
                    questsError = null
                )
            } catch (e: ClientRequestException) {
                val errorMessage = when (e.response.status.value) {
                    400 -> "Invalid request"
                    401 -> "Authentication required"
                    403 -> "Access denied"
                    404 -> "Quests not found"
                    else -> "Failed to load quests"
                }
                mutableState.value = mutableState.value.copy(
                    isLoadingQuests = false,
                    questsError = errorMessage
                )
            } catch (e: ServerResponseException) {
                mutableState.value = mutableState.value.copy(
                    isLoadingQuests = false,
                    questsError = "Server error. Please try again later."
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoadingQuests = false,
                    questsError = "Network error. Please check your connection."
                )
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
