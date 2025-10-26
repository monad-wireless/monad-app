package sk.martinvanco.monad.wifi_test_v2.presentation

data class WifiTestV2State(
    val ssid: String = "Veverka Devolo",
    val password: String = "Vancik1234",
    val isConnecting: Boolean = false,
    val statusMessage: String = "",
    val currentNetwork: String? = null,
    val lastError: String? = null
)
