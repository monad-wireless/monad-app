package sk.martinvanco.monad.ble.data

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.ble.domain.BleScanner
import kotlin.uuid.ExperimentalUuidApi

// Platform-specific scanner creation (configured for LOW_LATENCY on Android)
internal expect fun createPlatformScanner(): Scanner<Advertisement>

@OptIn(ExperimentalUuidApi::class)
class BleScannerImpl : BleScanner {

    private val scanner = createPlatformScanner()
    private val _isScanning = MutableStateFlow(false)

    override val isScanning: Flow<Boolean> = _isScanning.asStateFlow()

    override val advertisements: Flow<BleAdvertisement> = scanner.advertisements.map { ad ->
        BleAdvertisement(
            address = ad.identifier.toString(),
            name = ad.name,
            rssi = ad.rssi,
            manufacturerData = ad.manufacturerData?.let { mapOf(it.code to it.data) },
            serviceUuids = ad.uuids.map { it.toString() },
            rawData = buildRawDataString(ad)
        )
    }

    override suspend fun startScanning() {
        _isScanning.value = true
    }

    override fun stopScanning() {
        _isScanning.value = false
    }

    private fun buildRawDataString(ad: Advertisement): String {
        return buildString {
            append("Identifier: ${ad.identifier}\n")
            append("Name: ${ad.name ?: "N/A"}\n")
            append("Peripheral Name: ${ad.peripheralName ?: "N/A"}\n")
            append("RSSI: ${ad.rssi} dBm\n")
            ad.txPower?.let { append("TX Power: $it dBm\n") }
            ad.isConnectable?.let { append("Connectable: $it\n") }

            if (ad.uuids.isNotEmpty()) {
                append("Service UUIDs:\n")
                ad.uuids.forEach { uuid ->
                    append("  - $uuid\n")
                }
            }

            ad.manufacturerData?.let { mfgData ->
                append("Manufacturer Data:\n")
                append("  Company ID: 0x${mfgData.code.toString(16).uppercase()}\n")
                append("  Data: ${mfgData.data.joinToString(" ") { "0x${it.toString(16).uppercase().padStart(2, '0')}" }}\n")
            }
        }
    }
}
