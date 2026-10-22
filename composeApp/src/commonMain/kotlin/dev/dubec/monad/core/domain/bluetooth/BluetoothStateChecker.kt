package dev.dubec.monad.core.domain.bluetooth

interface BluetoothStateChecker {
    fun isBluetoothEnabled(): Boolean
}
