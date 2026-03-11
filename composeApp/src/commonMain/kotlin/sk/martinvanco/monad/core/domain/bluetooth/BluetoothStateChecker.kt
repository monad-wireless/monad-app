package sk.martinvanco.monad.core.domain.bluetooth

interface BluetoothStateChecker {
    fun isBluetoothEnabled(): Boolean
}
