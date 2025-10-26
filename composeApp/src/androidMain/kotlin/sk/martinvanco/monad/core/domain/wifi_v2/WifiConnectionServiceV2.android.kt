package sk.martinvanco.monad.core.domain.wifi_v2

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import sk.martinvanco.monad.core.util.ContextProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RequiresApi(Build.VERSION_CODES.Q)
actual class WifiConnectionServiceV2 {

    private val context: Context by lazy { ContextProvider.getContext() }
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var currentNetworkCallback: ConnectivityManager.NetworkCallback? = null

    actual suspend fun connect(
        ssid: String,
        password: String?,
        securityType: WifiSecurityType
    ): Result<Unit> {
        return try {
            // Check if WiFi is enabled
            if (!wifiManager.isWifiEnabled) {
                return Result.failure(Exception("WiFi is disabled"))
            }

            // Disconnect any previous connection
            disconnect()

            // Build the network specifier
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)

            // Add security configuration
            when (securityType) {
                WifiSecurityType.WPA2, WifiSecurityType.WPA3 -> {
                    if (password.isNullOrEmpty()) {
                        return Result.failure(Exception("Password required for secured network"))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (securityType == WifiSecurityType.WPA3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            specifierBuilder.setWpa3Passphrase(password)
                        } else {
                            specifierBuilder.setWpa2Passphrase(password)
                        }
                    }
                }
                WifiSecurityType.WEP -> {
                    return Result.failure(Exception("WEP is not supported on Android 10+"))
                }
                WifiSecurityType.OPEN -> {
                    // No password needed
                }
            }

            val specifier = specifierBuilder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            // Connect with timeout
            val connected = withTimeout(30000) { // 30 second timeout
                suspendCoroutine { continuation ->
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            currentNetworkCallback = this
                            continuation.resume(true)
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            continuation.resume(false)
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            if (currentNetworkCallback == this) {
                                currentNetworkCallback = null
                            }
                        }
                    }

                    connectivityManager.requestNetwork(request, callback)

                    // Set a backup timeout
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(29000)
                        try {
                            continuation.resume(false)
                        } catch (e: Exception) {
                            // Already resumed
                        }
                    }
                }
            }

            if (connected) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to connect. Please check the password and try again."))
            }

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(Exception("Connection timed out"))
        } catch (e: SecurityException) {
            Result.failure(Exception("Permission denied. Please grant WiFi permissions."))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unknown error"))
        }
    }

    actual suspend fun disconnect(): Result<Unit> {
        return try {
            currentNetworkCallback?.let { callback ->
                connectivityManager.unregisterNetworkCallback(callback)
                currentNetworkCallback = null
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual suspend fun getCurrentSsid(): String? {
        return try {
            val networkCapabilities = connectivityManager.activeNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
            }

            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Note: Getting SSID requires location permission on Android
                // This is a simplified implementation
                val wifiInfo = wifiManager.connectionInfo
                wifiInfo?.ssid?.removeSurrounding("\"")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
