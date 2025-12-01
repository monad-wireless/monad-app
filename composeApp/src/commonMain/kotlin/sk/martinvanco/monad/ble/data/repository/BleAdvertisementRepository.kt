package sk.martinvanco.monad.ble.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.BleAdvertisementRecord
import sk.martinvanco.monad.Database

class BleAdvertisementRepository(
    private val database: Database
) {
    private val queries = database.bleAdvertisementRecordQueries

    private val _recordCount = MutableStateFlow(0L)
    val recordCount: Flow<Long> = _recordCount.asStateFlow()

    suspend fun refreshRecordCount() = withContext(Dispatchers.IO) {
        _recordCount.value = queries.countAll().executeAsOne()
    }

    suspend fun getAll(): List<BleAdvertisementRecord> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList()
    }

    suspend fun getByQuestId(questId: String): List<BleAdvertisementRecord> = withContext(Dispatchers.IO) {
        queries.selectByQuestId(questId).executeAsList()
    }

    suspend fun getByQuestIdAndDevice(questId: String, deviceAddress: String): List<BleAdvertisementRecord> = withContext(Dispatchers.IO) {
        queries.selectByQuestIdAndDevice(questId, deviceAddress).executeAsList()
    }

    suspend fun countAll(): Long = withContext(Dispatchers.IO) {
        val count = queries.countAll().executeAsOne()
        _recordCount.value = count
        count
    }

    suspend fun countByQuestId(questId: String): Long = withContext(Dispatchers.IO) {
        queries.countByQuestId(questId).executeAsOne()
    }

    suspend fun insert(
        questId: String,
        timestamp: Long,
        deviceAddress: String,
        deviceName: String?,
        rssi: Int,
        manufacturerDataCompanyId: Int?,
        manufacturerDataBytes: String?,
        serviceUuids: String?
    ) = withContext(Dispatchers.IO) {
        queries.insert(
            questId = questId,
            timestamp = timestamp,
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            rssi = rssi.toLong(),
            manufacturerDataCompanyId = manufacturerDataCompanyId?.toLong(),
            manufacturerDataBytes = manufacturerDataBytes,
            serviceUuids = serviceUuids
        )
        _recordCount.value = queries.countAll().executeAsOne()
    }

    suspend fun deleteByQuestId(questId: String) = withContext(Dispatchers.IO) {
        queries.deleteByQuestId(questId)
        _recordCount.value = queries.countAll().executeAsOne()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAll()
        _recordCount.value = 0L
    }

    suspend fun getLatestByQuestId(questId: String): BleAdvertisementRecord? = withContext(Dispatchers.IO) {
        queries.getLatestByQuestId(questId).executeAsOneOrNull()
    }
}
