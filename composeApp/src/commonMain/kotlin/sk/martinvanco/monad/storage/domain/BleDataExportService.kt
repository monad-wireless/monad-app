package sk.martinvanco.monad.storage.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.ble.data.repository.BleAdvertisementRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.storage.data.api.StorageService
import sk.martinvanco.monad.storage.data.dto.UploadResponseDto

class BleDataExportService(
    private val bleAdvertisementRepository: BleAdvertisementRepository,
    private val userRepository: UserRepository,
    private val storageService: StorageService
) {
    suspend fun exportAndUpload(): Result<UploadResponseDto> = withContext(Dispatchers.IO) {
        try {
            val user = userRepository.getCurrentUser()
                ?: return@withContext Result.failure(Exception("User not logged in"))

            val token = user.token
                ?: return@withContext Result.failure(Exception("No auth token available"))

            val records = bleAdvertisementRepository.getAll()

            if (records.isEmpty()) {
                return@withContext Result.failure(Exception("No BLE data to export"))
            }

            val csvData = buildCsv(records)
            val timestamp = currentTimeMillis()
            val filename = "ble_data_${timestamp}.csv"

            val response = storageService.uploadFile(
                token = token,
                filename = filename,
                fileContent = csvData.encodeToByteArray()
            )

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportAndUploadThenClear(): Result<UploadResponseDto> = withContext(Dispatchers.IO) {
        val result = exportAndUpload()
        if (result.isSuccess) {
            bleAdvertisementRepository.deleteAll()
        }
        result
    }

    private fun buildCsv(records: List<sk.martinvanco.monad.BleAdvertisementRecord>): String {
        val sb = StringBuilder()

        // CSV header
        sb.appendLine("questId,timestamp,deviceAddress,deviceName,rssi,manufacturerDataCompanyId,manufacturerDataBytes,serviceUuids")

        // Data rows
        records.forEach { record ->
            sb.appendLine(
                listOf(
                    escapeCsv(record.questId),
                    record.timestamp.toString(),
                    escapeCsv(record.deviceAddress),
                    escapeCsv(record.deviceName ?: ""),
                    record.rssi.toString(),
                    record.manufacturerDataCompanyId?.toString() ?: "",
                    escapeCsv(record.manufacturerDataBytes ?: ""),
                    escapeCsv(record.serviceUuids ?: "")
                ).joinToString(",")
            )
        }

        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
