package sk.martinvanco.monad.ble.data

import android.bluetooth.le.ScanSettings
import com.juul.kable.Advertisement
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Scanner

/**
 * Creates an optimized BLE scanner for Android with LOW_LATENCY scan mode.
 * This provides real-time updates (~100-200ms) instead of batched results every 10 seconds.
 *
 * Note: LOW_LATENCY mode uses more battery, but is necessary for real-time distance tracking.
 */
@OptIn(ObsoleteKableApi::class)
internal actual fun createPlatformScanner(): Scanner<Advertisement> {
    val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Fastest scan mode
        .setReportDelay(0) // Report results immediately, don't batch
        .build()

    return Scanner {
        this.scanSettings = scanSettings
    }
}
