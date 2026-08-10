package sk.martinvanco.monad.core.domain.wifi

/**
 * Custom WiFi connection service with better error handling
 * Based on analysis of kmm-wifi-connect library with improvements
 */

enum class WifiSecurityType {
    WPA2,
    WPA3,
    WEP,
    OPEN
}

sealed class WifiError {
    data object WrongPassword : WifiError()
    data object NetworkNotFound : WifiError()
    data object UserCancelled : WifiError()
    data object Timeout : WifiError()
    data object BluetoothConflict : WifiError()
    data object PermissionDenied : WifiError()
    data class Unknown(val message: String) : WifiError()
}

/**
 * Platform-specific WiFi connection service
 */
expect class WifiConnectionService() {
    /**
     * Connect to a WiFi network
     *
     * @param ssid Network name
     * @param password Network password (null for open networks)
     * @param securityType Security type of the network
     * @return Result with success or specific error
     */
    suspend fun connect(
        ssid: String,
        password: String?,
        securityType: WifiSecurityType
    ): Result<Unit>

    /**
     * Disconnect from current WiFi network
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Get currently connected WiFi SSID
     */
    suspend fun getCurrentSsid(): String?
}
