package sk.martinvanco.monad.wifi_test_v2.presentation

data class WifiTestV2State(
    val ssid: String = "",
    val password: String = "",
    val isConnecting: Boolean = false,
    val statusMessage: String = "",
    val currentNetwork: String? = null,
    val lastError: String? = null
)
