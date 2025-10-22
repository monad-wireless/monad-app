package sk.martinvanco.monad.wifi_test.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import net.mattiascibien.wificonnect.WiFiType
import sk.martinvanco.monad.core.domain.wifi.WifiConnectionResult
import sk.martinvanco.monad.core.domain.wifi.WifiConnectionService
import sk.martinvanco.monad.core.util.Platform

class WifiTestScreenModel(
    private val wifiService: WifiConnectionService
) : StateScreenModel<WifiTestState>(
    WifiTestState(
        platformWarning = if (Platform.isIOS) {
            "⚠️ iOS WiFi connection requires a paid Apple Developer Program membership ($99/year). " +
            "This feature only works on Android with free developer accounts."
        } else ""
    )
) {

    fun onEvent(event: WifiTestEvent) {
        when (event) {
            is WifiTestEvent.UpdateSsid -> {
                mutableState.value = state.value.copy(ssid = event.ssid)
            }

            is WifiTestEvent.UpdatePassword -> {
                mutableState.value = state.value.copy(password = event.password)
            }

            WifiTestEvent.ConnectToNetwork -> {
                connectToWifi(
                    ssid = state.value.ssid,
                    password = state.value.password
                )
            }

            WifiTestEvent.ConnectToYnet -> {
                connectToWifi(
                    ssid = "Ynet",
                    password = "password123"
                )
            }
        }
    }

    private fun connectToWifi(ssid: String, password: String) {
        screenModelScope.launch {
            mutableState.value = state.value.copy(
                isConnecting = true,
                statusMessage = "Connecting to $ssid...",
                currentNetwork = ssid
            )

            Napier.d("Attempting to connect to WiFi: $ssid")

            val result = wifiService.connectToNetwork(
                ssid = ssid,
                password = password,
                securityType = WiFiType.Wpa2
            )

            when (result) {
                is WifiConnectionResult.Success -> {
                    Napier.i("Successfully connected to: $ssid")
                    mutableState.value = state.value.copy(
                        isConnecting = false,
                        statusMessage = "✓ Successfully connected to $ssid",
                        currentNetwork = ""
                    )
                }

                is WifiConnectionResult.Error -> {
                    Napier.e("Failed to connect to $ssid: ${result.message}")
                    mutableState.value = state.value.copy(
                        isConnecting = false,
                        statusMessage = "✗ Error: ${result.message}",
                        currentNetwork = ""
                    )
                }
            }
        }
    }
}
