package sk.martinvanco.monad.device.presentation

sealed interface DeviceEvent {
    data object Retry : DeviceEvent
}
