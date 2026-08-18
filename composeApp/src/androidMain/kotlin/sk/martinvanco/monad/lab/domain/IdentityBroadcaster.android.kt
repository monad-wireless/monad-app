package sk.martinvanco.monad.lab.domain

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android broadcaster: `BluetoothLeAdvertiser`, non-connectable legacy advertising.
 *
 * The commanded interval cannot be passed through — Android exposes three buckets — so the mapping
 * is explicit and the *bucket* is what the report carries. The frame is a single 128-bit service
 * UUID (18 bytes of AD), which fits the 31-byte legacy budget with the flags; device name and TX
 * power AD are excluded to keep it that way.
 */
actual class IdentityBroadcaster {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    private val _isBroadcasting = MutableStateFlow(false)
    actual val isBroadcasting: Flow<Boolean> = _isBroadcasting.asStateFlow()

    actual suspend fun start(request: BroadcastRequest): Result<BroadcastReport> {
        if (_isBroadcasting.value) {
            return Result.failure(IllegalStateException("already broadcasting"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            return Result.failure(SecurityException("BLUETOOTH_ADVERTISE not granted"))
        }
        val adapter = bluetoothManager()?.adapter
            ?: return Result.failure(IllegalStateException("no Bluetooth adapter"))
        // Null both when the hardware cannot advertise and when Bluetooth is off — the user-facing
        // remedy is the same ("turn Bluetooth on"), so one message covers both honestly.
        val leAdvertiser = adapter.bluetoothLeAdvertiser
            ?: return Result.failure(IllegalStateException("advertiser unavailable (Bluetooth off, or unsupported hardware)"))

        val uuid = runCatching { ParcelUuid.fromString(request.serviceUuid) }.getOrElse {
            return Result.failure(IllegalArgumentException("malformed service UUID: ${request.serviceUuid}"))
        }

        val (mode, bucket) = intervalBucket(request.intervalMs)
        val txPower = txPowerLevel(request.txPower)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(txPower)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(uuid)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val started = CompletableDeferred<Result<Unit>>()
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                started.complete(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                started.complete(Result.failure(IllegalStateException(describeFailure(errorCode))))
            }
        }

        callback = advertiseCallback
        advertiser = leAdvertiser
        runCatching { leAdvertiser.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { return Result.failure(it) }

        val outcome = withTimeoutOrNull(START_TIMEOUT_MILLIS) { started.await() }
            ?: Result.failure(IllegalStateException("advertiser gave no answer within ${START_TIMEOUT_MILLIS} ms"))

        return outcome.fold(
            onSuccess = {
                _isBroadcasting.value = true
                Napier.i("[lab] android broadcast on air: ${request.serviceUuid} ($bucket)")
                Result.success(
                    BroadcastReport(
                        serviceUuid = request.serviceUuid,
                        requestedIntervalMs = request.intervalMs,
                        acceptedInterval = bucket,
                        txPower = request.txPower,
                        foregroundOnly = false,
                        notes = listOf("legacy ADV_NONCONN_IND, 128-bit service UUID"),
                    )
                )
            },
            onFailure = {
                stop()
                Result.failure(it)
            },
        )
    }

    actual fun stop() {
        val leAdvertiser = advertiser
        val advertiseCallback = callback
        if (leAdvertiser != null && advertiseCallback != null) {
            // Throws SecurityException when Bluetooth was toggled off underneath us; the
            // advertisement is already gone in that case, so the intent is satisfied either way.
            runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }
        }
        advertiser = null
        callback = null
        _isBroadcasting.value = false
    }

    actual fun diagnostics(): List<String> = buildList {
        val adapter = bluetoothManager()?.adapter
        add("adapter=${adapter != null}")
        add("bluetooth_enabled=${adapter?.isEnabled == true}")
        add("advertiser=${adapter?.bluetoothLeAdvertiser != null}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("advertise_permission=${hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)}")
        } else {
            add("advertise_permission=implicit (pre-Android 12)")
        }
        add("background=survives via the foreground service, like the witness")
    }

    private fun bluetoothManager(): BluetoothManager? =
        (ContextProvider.getContext() as Context)
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            ContextProvider.getContext() as Context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED

    private fun intervalBucket(intervalMs: Int): Pair<Int, String> = when {
        intervalMs <= 150 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY to "low_latency (~100 ms)"
        intervalMs <= 600 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED to "balanced (~250 ms)"
        else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER to "low_power (~1000 ms)"
    }

    private fun txPowerLevel(txPower: String): Int = when (txPower.lowercase()) {
        "ultra_low" -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
        "low" -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        "high" -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        else -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
    }

    private fun describeFailure(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertise failed: data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "advertise failed: too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "advertise failed: already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "advertise failed: internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "advertise failed: unsupported on this hardware"
        else -> "advertise failed: code $errorCode"
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 5_000L
    }
}
