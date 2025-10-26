package sk.martinvanco.monad.core.domain.wifi_v2

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.Foundation.NSError
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import platform.NetworkExtension.NEHotspotNetwork
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
actual class WifiConnectionServiceV2 {

    actual suspend fun connect(
        ssid: String,
        password: String?,
        securityType: WifiSecurityType
    ): Result<Unit> {
        Napier.d("🔵 [WiFiV2] Starting connection attempt to: $ssid")
        Napier.d("🔵 [WiFiV2] Password length: ${password?.length ?: 0}")
        Napier.d("🔵 [WiFiV2] Security type: $securityType")

        return try {
            val result = suspendCoroutine { continuation ->
                // Create configuration
                val config = when {
                    password == null -> {
                        Napier.d("🔵 [WiFiV2] Creating OPEN network configuration")
                        NEHotspotConfiguration(sSID = ssid)
                    }
                    securityType == WifiSecurityType.WEP -> {
                        Napier.d("🔵 [WiFiV2] Creating WEP network configuration")
                        NEHotspotConfiguration(sSID = ssid, passphrase = password, isWEP = true)
                    }
                    else -> {
                        Napier.d("🔵 [WiFiV2] Creating WPA2/WPA3 network configuration")
                        NEHotspotConfiguration(sSID = ssid, passphrase = password, isWEP = false)
                    }
                }

                // Join network to system
                config.joinOnce = false // Stay connected
                Napier.d("🔵 [WiFiV2] Configuration created with joinOnce = false")

                // Apply configuration
                Napier.d("🔵 [WiFiV2] Calling applyConfiguration...")
                NEHotspotConfigurationManager.sharedManager.applyConfiguration(config) { error: NSError? ->
                    if (error != null) {
                        // Real error occurred
                        val errorCode = error.code
                        val errorDomain = error.domain
                        val errorMessage = error.localizedDescription ?: "Unknown error"
                        val errorUserInfo = error.userInfo.toString()

                        Napier.e("🔴 [WiFiV2] ERROR RECEIVED FROM iOS:")
                        Napier.e("🔴 [WiFiV2]   Error Code: $errorCode")
                        Napier.e("🔴 [WiFiV2]   Error Domain: $errorDomain")
                        Napier.e("🔴 [WiFiV2]   Error Message: $errorMessage")
                        Napier.e("🔴 [WiFiV2]   Error UserInfo: $errorUserInfo")
                        Napier.e("🔴 [WiFiV2]   Error.code as Int: ${errorCode.toInt()}")

                        val specificError = when (errorCode.toInt()) {
                            1 -> {
                                Napier.e("🔴 [WiFiV2] Mapped to: User Cancelled")
                                WifiError.UserCancelled
                            }
                            2 -> {
                                Napier.e("🔴 [WiFiV2] Mapped to: Network Not Found")
                                WifiError.NetworkNotFound
                            }
                            3 -> {
                                Napier.e("🔴 [WiFiV2] Mapped to: Wrong Password")
                                WifiError.WrongPassword
                            }
                            4 -> {
                                Napier.e("🔴 [WiFiV2] Mapped to: Timeout")
                                WifiError.Timeout
                            }
                            else -> {
                                Napier.e("🔴 [WiFiV2] Mapped to: Unknown Error - $errorMessage")
                                WifiError.Unknown(errorMessage)
                            }
                        }

                        continuation.resume(Result.failure(Exception(parseError(specificError))))
                    } else {
                        // No error from applyConfiguration
                        Napier.i("✅ [WiFiV2] applyConfiguration completed with NO ERROR")
                        Napier.i("✅ [WiFiV2] Configuration applied successfully")

                        // Network connection is initiated, will complete asynchronously
                        continuation.resume(Result.success(Unit))
                    }
                }
            }

            Napier.d("🔵 [WiFiV2] applyConfiguration callback returned. Result: ${if (result.isSuccess) "SUCCESS" else "FAILURE"}")

            // If configuration was applied successfully, wait and verify
            if (result.isSuccess) {
                Napier.d("🔵 [WiFiV2] Waiting 2 seconds for connection to establish...")
                delay(2000)

                Napier.d("🔵 [WiFiV2] Checking current SSID...")
                val currentSsid = getCurrentSsid()
                Napier.d("🔵 [WiFiV2] Current SSID: ${currentSsid ?: "null"} (expected: $ssid)")

                if (currentSsid == ssid) {
                    Napier.i("✅ [WiFiV2] Connection VERIFIED! Successfully connected to: $ssid")
                    Result.success(Unit)
                } else {
                    Napier.e("🔴 [WiFiV2] Connection verification FAILED")
                    Napier.e("🔴 [WiFiV2] iOS accepted the configuration but network did not become active")
                    Napier.e("🔴 [WiFiV2] This typically means wrong password or network out of range")
                    Result.failure(Exception("Failed to connect to network. Please check the password and signal strength."))
                }
            } else {
                Napier.e("🔴 [WiFiV2] Returning failure result")
                result
            }

        } catch (e: Exception) {
            Napier.e("🔴 [WiFiV2] EXCEPTION caught: ${e.message}", e)
            Result.failure(Exception(e.message ?: "Unknown error"))
        }
    }

    actual suspend fun disconnect(): Result<Unit> {
        return try {
            // Note: iOS doesn't provide a direct API to disconnect from WiFi programmatically
            // The best we can do is remove the configuration
            val currentSsid = getCurrentSsid()
            if (currentSsid != null) {
                suspendCoroutine { continuation ->
                    NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(currentSsid)
                    continuation.resume(Result.success(Unit))
                }
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual suspend fun getCurrentSsid(): String? {
        return try {
            suspendCoroutine { continuation ->
                NEHotspotNetwork.fetchCurrentWithCompletionHandler { network ->
                    val ssid = network?.SSID
                    if (ssid != null) {
                        Napier.d("🔵 [WiFiV2] fetchCurrentWithCompletionHandler returned SSID: $ssid")
                    } else {
                        Napier.w("⚠️ [WiFiV2] fetchCurrentWithCompletionHandler returned null")
                        Napier.w("⚠️ [WiFiV2] This usually means:")
                        Napier.w("⚠️ [WiFiV2]   1. Not connected to WiFi")
                        Napier.w("⚠️ [WiFiV2]   2. Missing location permission")
                        Napier.w("⚠️ [WiFiV2]   3. Connection still in progress")
                    }
                    continuation.resume(ssid)
                }
            }
        } catch (e: Exception) {
            Napier.e("🔴 [WiFiV2] Exception in getCurrentSsid: ${e.message}", e)
            null
        }
    }

    private fun parseError(error: WifiError): String {
        return when (error) {
            is WifiError.WrongPassword -> "Authentication failed. Please check the password."
            is WifiError.NetworkNotFound -> "Network not found. Please check the SSID."
            is WifiError.UserCancelled -> "Connection cancelled by user."
            is WifiError.Timeout -> "Connection timed out."
            is WifiError.BluetoothConflict -> "Bluetooth conflict detected."
            is WifiError.PermissionDenied -> "Permission denied."
            is WifiError.Unknown -> error.message
        }
    }
}
