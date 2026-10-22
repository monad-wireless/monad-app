package dev.dubec.monad.device.presentation

sealed interface DeviceEvent {
    data object Retry : DeviceEvent
}
