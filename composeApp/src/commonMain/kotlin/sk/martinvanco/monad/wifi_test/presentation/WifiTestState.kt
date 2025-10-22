package sk.martinvanco.monad.wifi_test.presentation

data class WifiTestState(
    val ssid: String = "",
    val password: String = "",
    val isConnecting: Boolean = false,
    val statusMessage: String = "",
    val currentNetwork: String = "",
    val platformWarning: String = ""
)
