package sk.martinvanco.monad.core.domain.bluetooth

class IosBluetoothStateChecker : BluetoothStateChecker {
    override fun isBluetoothEnabled(): Boolean {
        // iOS handles BT state through CoreBluetooth delegate
        return true
    }
}
