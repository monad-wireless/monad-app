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
import io.github.aakira.napier.Napier
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
        var networkCallback: ConnectivityManager.NetworkCallback? = null
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
                    var resumed = false
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Napier.i("🟢 [WiFiV2-Android] onAvailable - Network connected!")
                            if (!resumed) {
                                resumed = true
                                currentNetworkCallback = this
                                continuation.resume(true)
                            }
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            Napier.e("🔴 [WiFiV2-Android] onUnavailable - Connection failed (wrong password or network unavailable)")
                            if (!resumed) {
                                resumed = true
                                // Unregister immediately on failure
                                try {
                                    Napier.d("🔵 [WiFiV2-Android] Unregistering network callback after failure")
                                    connectivityManager.unregisterNetworkCallback(this)
                                } catch (e: Exception) {
                                    Napier.w("⚠️ [WiFiV2-Android] Callback already unregistered: ${e.message}")
                                }
                                continuation.resume(false)
                            }
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Napier.w("⚠️ [WiFiV2-Android] onLost - Network connection lost")
                            if (currentNetworkCallback == this) {
                                currentNetworkCallback = null
                            }
                        }
                    }

                    networkCallback = callback
                    Napier.d("🔵 [WiFiV2-Android] Requesting network connection to: $ssid")
                    connectivityManager.requestNetwork(request, callback)

                    // Set a backup timeout
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(29000)
                        if (!resumed) {
                            resumed = true
                            try {
                                connectivityManager.unregisterNetworkCallback(callback)
                            } catch (e: Exception) {
                                // Already unregistered
                            }
                            try {
                                continuation.resume(false)
                            } catch (e: Exception) {
                                // Already resumed
                            }
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
            // Cleanup on timeout
            networkCallback?.let { callback ->
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (ex: Exception) {
                    // Already unregistered
                }
            }
            Result.failure(Exception("Connection timed out"))
        } catch (e: SecurityException) {
            Result.failure(Exception("Permission denied. Please grant WiFi permissions."))
        } catch (e: Exception) {
            // Cleanup on any other exception
            networkCallback?.let { callback ->
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (ex: Exception) {
                    // Already unregistered
                }
            }
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
