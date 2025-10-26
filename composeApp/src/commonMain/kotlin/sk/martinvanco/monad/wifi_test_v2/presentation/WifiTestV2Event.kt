package sk.martinvanco.monad.wifi_test_v2.presentation

sealed class WifiTestV2Event {
    data class UpdateSsid(val ssid: String) : WifiTestV2Event()
    data class UpdatePassword(val password: String) : WifiTestV2Event()
    data object ConnectToNetwork : WifiTestV2Event()
    data object DisconnectFromNetwork : WifiTestV2Event()
    data object CheckCurrentNetwork : WifiTestV2Event()
}
