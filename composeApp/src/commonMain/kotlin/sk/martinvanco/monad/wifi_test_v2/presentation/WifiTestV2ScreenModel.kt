package sk.martinvanco.monad.wifi_test_v2.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import sk.martinvanco.monad.core.domain.wifi_v2.WifiConnectionServiceV2
import sk.martinvanco.monad.core.domain.wifi_v2.WifiSecurityType

class WifiTestV2ScreenModel(
    private val wifiService: WifiConnectionServiceV2
) : StateScreenModel<WifiTestV2State>(
    WifiTestV2State()
) {

    init {
        checkCurrentNetwork()
    }

    fun onEvent(event: WifiTestV2Event) {
        when (event) {
            is WifiTestV2Event.UpdateSsid -> {
                mutableState.value = state.value.copy(ssid = event.ssid)
            }

            is WifiTestV2Event.UpdatePassword -> {
                mutableState.value = state.value.copy(password = event.password)
            }

            WifiTestV2Event.ConnectToNetwork -> {
                connectToWifi()
            }

            WifiTestV2Event.DisconnectFromNetwork -> {
                disconnectFromWifi()
            }

            WifiTestV2Event.CheckCurrentNetwork -> {
                checkCurrentNetwork()
            }
        }
    }

    private fun connectToWifi() {
        val ssid = state.value.ssid
        val password = state.value.password

        screenModelScope.launch {
            mutableState.value = state.value.copy(
                isConnecting = true,
                statusMessage = "Connecting to $ssid...",
                lastError = null
            )

            Napier.d("Attempting to connect to WiFi: $ssid")

            val result = wifiService.connect(
                ssid = ssid,
                password = password.ifEmpty { null },
                securityType = if (password.isEmpty()) WifiSecurityType.OPEN else WifiSecurityType.WPA2
            )

            result.onSuccess {
                Napier.i("Successfully connected to: $ssid")

                // Check current network after connection
                val currentSsid = wifiService.getCurrentSsid()

                mutableState.value = state.value.copy(
                    isConnecting = false,
                    statusMessage = "✓ Successfully connected to $ssid",
                    currentNetwork = currentSsid,
                    lastError = null
                )
            }.onFailure { error ->
                val errorMessage = error.message ?: "Unknown error"
                Napier.e("Failed to connect to $ssid: $errorMessage")

                mutableState.value = state.value.copy(
                    isConnecting = false,
                    statusMessage = "✗ Failed to connect",
                    lastError = errorMessage,
                    currentNetwork = null
                )
            }
        }
    }

    private fun disconnectFromWifi() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(
                statusMessage = "Disconnecting..."
            )

            val result = wifiService.disconnect()

            result.onSuccess {
                Napier.i("Disconnected from WiFi")
                mutableState.value = state.value.copy(
                    statusMessage = "Disconnected",
                    currentNetwork = null
                )
            }.onFailure { error ->
                Napier.e("Failed to disconnect: ${error.message}")
                mutableState.value = state.value.copy(
                    statusMessage = "Failed to disconnect",
                    lastError = error.message
                )
            }
        }
    }

    private fun checkCurrentNetwork() {
        screenModelScope.launch {
            val currentSsid = wifiService.getCurrentSsid()
            Napier.d("Current WiFi network: ${currentSsid ?: "None"}")

            mutableState.value = state.value.copy(
                currentNetwork = currentSsid,
                statusMessage = if (currentSsid != null) {
                    "Connected to: $currentSsid"
                } else {
                    "Not connected to WiFi"
                }
            )
        }
    }
}
