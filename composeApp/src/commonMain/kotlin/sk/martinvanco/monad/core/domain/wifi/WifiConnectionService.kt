package sk.martinvanco.monad.core.domain.wifi

import net.mattiascibien.wificonnect.WiFiType
import net.mattiascibien.wificonnect.connectToWifi

sealed class WifiConnectionResult {
    data object Success : WifiConnectionResult()
    data class Error(val message: String) : WifiConnectionResult()
}

class WifiConnectionService {

    suspend fun connectToNetwork(
        ssid: String,
        password: String,
        securityType: WiFiType = WiFiType.Wpa2
    ): WifiConnectionResult {
        return try {
            val result = connectToWifi(
                ssid = ssid,
                type = securityType,
                password = password
            )

            if (result) {
                WifiConnectionResult.Success
            } else {
                WifiConnectionResult.Error("Failed to connect to network: $ssid")
            }
        } catch (e: Exception) {
            WifiConnectionResult.Error("Error connecting to WiFi: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun connectToUnsecuredNetwork(ssid: String): WifiConnectionResult {
        return try {
            val result = connectToWifi(
                ssid = ssid,
                type = WiFiType.Unsecured,
                password = null
            )

            if (result) {
                WifiConnectionResult.Success
            } else {
                WifiConnectionResult.Error("Failed to connect to unsecured network: $ssid")
            }
        } catch (e: Exception) {
            WifiConnectionResult.Error("Error connecting to WiFi: ${e.message ?: "Unknown error"}")
        }
    }
}
