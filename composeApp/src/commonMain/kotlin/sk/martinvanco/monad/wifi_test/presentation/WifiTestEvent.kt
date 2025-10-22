package sk.martinvanco.monad.wifi_test.presentation

sealed class WifiTestEvent {
    data class UpdateSsid(val ssid: String) : WifiTestEvent()
    data class UpdatePassword(val password: String) : WifiTestEvent()
    data object ConnectToNetwork : WifiTestEvent()
    data object ConnectToYnet : WifiTestEvent()
}
