package sk.martinvanco.monad.ble.domain

import kotlinx.coroutines.flow.Flow

interface BleScanner {
    val advertisements: Flow<BleAdvertisement>
    val isScanning: Flow<Boolean>

    suspend fun startScanning()
    fun stopScanning()
}

data class BleAdvertisement(
    val address: String,
    val name: String?,
    val rssi: Int,
    val manufacturerData: Map<Int, ByteArray>?,
    val serviceUuids: List<String>?,
    val rawData: String
)
