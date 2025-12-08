package sk.martinvanco.monad.ble.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sk.martinvanco.monad.ble.data.repository.BleAdvertisementRepository
import sk.martinvanco.monad.core.util.currentTimeMillis

class BleSensingService(
    private val bleScanner: BleScanner,
    private val bleAdvertisementRepository: BleAdvertisementRepository
) {
    private var sensingScope: CoroutineScope? = null
    private var sensingJob: Job? = null

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: Flow<Boolean> = _isCollecting.asStateFlow()

    private val _currentQuestId = MutableStateFlow<String?>(null)
    val currentQuestId: Flow<String?> = _currentQuestId.asStateFlow()

    val recordCount: Flow<Long> = bleAdvertisementRepository.recordCount

    companion object {
        private val ALLOWED_DEVICE_NAMES = setOf(
            "MONAD", "MONAD1", "MONAD2", "MONAD3", "MONAD4", "MONAD5", "MONAD6", "MONAD7"
        )
    }

    suspend fun startSensing(questId: String): Result<Unit> {
        if (_isCollecting.value) {
            return Result.failure(Exception("Already collecting BLE data"))
        }

        val scanResult = bleScanner.startScanning()
        if (scanResult.isFailure) {
            return scanResult
        }

        _currentQuestId.value = questId
        _isCollecting.value = true

        sensingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        sensingJob = bleScanner.advertisements
            .filter { advertisement ->
                advertisement.name?.uppercase() in ALLOWED_DEVICE_NAMES
            }
            .onEach { advertisement ->
                saveAdvertisement(questId, advertisement)
            }
            .launchIn(sensingScope!!)

        return Result.success(Unit)
    }

    fun stopSensing() {
        sensingJob?.cancel()
        sensingJob = null
        sensingScope?.cancel()
        sensingScope = null
        bleScanner.stopScanning()
        _isCollecting.value = false
        _currentQuestId.value = null
    }

    private suspend fun saveAdvertisement(questId: String, advertisement: BleAdvertisement) {
        val timestamp = currentTimeMillis()

        val manufacturerDataCompanyId = advertisement.manufacturerData?.keys?.firstOrNull()
        val manufacturerDataBytes = advertisement.manufacturerData?.values?.firstOrNull()
            ?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        val serviceUuidsString = advertisement.serviceUuids?.joinToString(",")

        bleAdvertisementRepository.insert(
            questId = questId,
            timestamp = timestamp,
            deviceAddress = advertisement.address,
            deviceName = advertisement.name,
            rssi = advertisement.rssi,
            manufacturerDataCompanyId = manufacturerDataCompanyId,
            manufacturerDataBytes = manufacturerDataBytes,
            serviceUuids = serviceUuidsString
        )
    }

    suspend fun getRecordCountForQuest(questId: String): Long {
        return bleAdvertisementRepository.countByQuestId(questId)
    }

    suspend fun getTotalRecordCount(): Long {
        return bleAdvertisementRepository.countAll()
    }

    suspend fun clearAllRecords() {
        bleAdvertisementRepository.deleteAll()
    }

    suspend fun clearRecordsForQuest(questId: String) {
        bleAdvertisementRepository.deleteByQuestId(questId)
    }

    suspend fun refreshRecordCount() {
        bleAdvertisementRepository.refreshRecordCount()
    }
}
