package sk.martinvanco.monad.core.domain.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context

class AndroidBluetoothStateChecker(
    private val context: Context
) : BluetoothStateChecker {
    override fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }
}
