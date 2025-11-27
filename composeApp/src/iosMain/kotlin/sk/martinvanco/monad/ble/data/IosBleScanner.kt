package sk.martinvanco.monad.ble.data

import com.juul.kable.Advertisement
import com.juul.kable.Scanner

/**
 * Creates a BLE scanner for iOS with default settings.
 * iOS doesn't have the same scan mode configuration as Android.
 */
internal actual fun createPlatformScanner(): Scanner<Advertisement> {
    return Scanner()
}
