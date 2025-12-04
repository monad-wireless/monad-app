package sk.martinvanco.monad.quests.domain

import sk.martinvanco.monad.ble.data.repository.BleAdvertisementRepository
import sk.martinvanco.monad.core.util.currentTimeMillis

class QuestDataExportService(
    private val bleAdvertisementRepository: BleAdvertisementRepository
) {
    /**
     * Generate TSV file content for BLE advertisement data
     * Format:
     * timestamp\tdevice_address\tdevice_name\trssi\tmanufacturer_id\tmanufacturer_data\tservice_uuids
     *
     * @param questId The quest ID to export data for
     * @return ByteArray of TSV content
     */
    suspend fun generateBleDataTsv(questId: String): ByteArray {
        val records = bleAdvertisementRepository.getByQuestId(questId)

        val header = "timestamp\tdevice_address\tdevice_name\trssi\tmanufacturer_id\tmanufacturer_data\tservice_uuids"

        val lines = buildString {
            appendLine(header)
            records.forEach { record ->
                append(record.timestamp)
                append("\t")
                append(record.deviceAddress)
                append("\t")
                append(record.deviceName ?: "")
                append("\t")
                append(record.rssi)
                append("\t")
                append(record.manufacturerDataCompanyId ?: "")
                append("\t")
                append(record.manufacturerDataBytes ?: "")
                append("\t")
                append(record.serviceUuids ?: "")
                appendLine()
            }
        }

        return lines.encodeToByteArray()
    }

    /**
     * Generate metadata TSV file content
     * Format:
     * key\tvalue
     *
     * @param questId Quest ID
     * @param enrollmentId Enrollment ID from backend
     * @param startTime Quest start time (ISO 8601)
     * @param endTime Quest end time (ISO 8601)
     * @param status Quest completion status
     * @param totalBleRecords Number of BLE records collected
     */
    suspend fun generateMetadataTsv(
        questId: String,
        enrollmentId: String,
        startTime: String,
        endTime: String,
        status: String,
        totalBleRecords: Long
    ): ByteArray {
        val now = currentTimeMillis()

        val lines = buildString {
            appendLine("key\tvalue")
            appendLine("quest_id\t$questId")
            appendLine("enrollment_id\t$enrollmentId")
            appendLine("start_time\t$startTime")
            appendLine("end_time\t$endTime")
            appendLine("status\t$status")
            appendLine("total_ble_records\t$totalBleRecords")
            appendLine("app_version\t0.1.0")
            appendLine("export_time\t${now}")
            appendLine("timezone\t${getDeviceTimezone()}")
            // Platform-specific device info would go here
            // For now, we add placeholders that can be filled by platform code
            appendLine("platform\t${getPlatformName()}")
        }

        return lines.encodeToByteArray()
    }

    /**
     * Get the total number of BLE records for a quest
     */
    suspend fun getBleRecordCount(questId: String): Long {
        return bleAdvertisementRepository.countByQuestId(questId)
    }
}

// Platform-specific function to get platform name
expect fun getPlatformName(): String

// Platform-specific function to get device timezone
expect fun getDeviceTimezone(): String
