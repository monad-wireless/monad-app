package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * iOS broadcaster: `CBPeripheralManager`, foreground-honest by declaration.
 *
 * iOS accepts exactly two advertisement keys — service UUIDs and a local name — and silently drops
 * everything else, which is why the lab frame is a service UUID and not an iBeacon (manufacturer
 * data never leaves an iOS peripheral). The interval and TX power cannot be commanded at all.
 *
 * **Backgrounded, the frame the fleet can read disappears**: iOS moves the service UUID into the
 * Apple-proprietary "overflow area", decodable only by other iOS scanners filtering for that exact
 * UUID — a raw HCI scanner on a Pi sees nothing attributable. The report therefore says
 * `foregroundOnly = true`, and the quest step that drives this role keeps the participant in-app.
 * The local name is deliberately not advertised: it would be dropped on backgrounding anyway, and
 * the UUID already carries the whole identity.
 */
@OptIn(ExperimentalForeignApi::class)
actual class IdentityBroadcaster {

    private var manager: CBPeripheralManager? = null
    private var delegate: BroadcasterDelegate? = null

    private val _isBroadcasting = MutableStateFlow(false)
    actual val isBroadcasting: Flow<Boolean> = _isBroadcasting.asStateFlow()

    actual suspend fun start(request: BroadcastRequest): Result<BroadcastReport> {
        if (_isBroadcasting.value) {
            return Result.failure(IllegalStateException("already broadcasting"))
        }
        if (AdvertiseIdentity.parseUuid(request.serviceUuid) == null) {
            return Result.failure(IllegalArgumentException("malformed service UUID: ${request.serviceUuid}"))
        }

        val handler = BroadcasterDelegate()
        delegate = handler
        // Instantiating the manager is what triggers the Bluetooth permission prompt; the
        // NSBluetoothAlwaysUsageDescription string covers broadcasting.
        val peripheral = CBPeripheralManager(delegate = handler, queue = null)
        manager = peripheral

        val poweredOn = withTimeoutOrNull(POWER_ON_TIMEOUT_MILLIS) { handler.powered.await() }
        if (poweredOn == null) {
            stop()
            return Result.failure(IllegalStateException("Bluetooth state undecided after ${POWER_ON_TIMEOUT_MILLIS} ms"))
        }
        if (poweredOn.isFailure) {
            stop()
            return Result.failure(poweredOn.exceptionOrNull() ?: IllegalStateException("Bluetooth unavailable"))
        }

        peripheral.startAdvertising(
            mapOf<Any?, Any?>(
                CBAdvertisementDataServiceUUIDsKey to listOf(CBUUID.UUIDWithString(request.serviceUuid)),
            )
        )

        val started = withTimeoutOrNull(START_TIMEOUT_MILLIS) { handler.started.await() }
        if (started == null || started.isFailure) {
            stop()
            return Result.failure(
                started?.exceptionOrNull()
                    ?: IllegalStateException("advertiser gave no answer within ${START_TIMEOUT_MILLIS} ms")
            )
        }

        _isBroadcasting.value = true
        Napier.i("[lab] ios broadcast on air: ${request.serviceUuid} (foreground only)")
        return Result.success(
            BroadcastReport(
                serviceUuid = request.serviceUuid,
                requestedIntervalMs = request.intervalMs,
                acceptedInterval = "system-controlled",
                txPower = "system-controlled",
                foregroundOnly = true,
                notes = listOf(
                    "iOS: interval and TX power are not commandable",
                    "backgrounding moves the UUID into the overflow area — invisible to the fleet",
                ),
            )
        )
    }

    actual fun stop() {
        manager?.let { peripheral ->
            if (peripheral.isAdvertising()) peripheral.stopAdvertising()
            peripheral.delegate = null
        }
        manager = null
        delegate = null
        _isBroadcasting.value = false
    }

    actual fun diagnostics(): List<String> = listOf(
        "framework=CBPeripheralManager (service UUID + nothing else)",
        "manager=${if (manager != null) describeState(manager!!.state) else "not created (created on start)"}",
        "interval=system-controlled",
        "foreground_only=true (overflow area is unreadable by a raw scanner)",
    )

    private fun describeState(state: Long): String = when (state) {
        CBManagerStatePoweredOn -> "powered on"
        CBManagerStatePoweredOff -> "powered off"
        CBManagerStateUnauthorized -> "unauthorized"
        CBManagerStateUnsupported -> "unsupported"
        else -> "undetermined"
    }

    private companion object {
        const val POWER_ON_TIMEOUT_MILLIS = 10_000L
        const val START_TIMEOUT_MILLIS = 5_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BroadcasterDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {

    val powered = CompletableDeferred<Result<Unit>>()
    val started = CompletableDeferred<Result<Unit>>()

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        if (powered.isCompleted) return
        when (peripheral.state) {
            CBManagerStatePoweredOn -> powered.complete(Result.success(Unit))
            CBManagerStatePoweredOff ->
                powered.complete(Result.failure(IllegalStateException("Bluetooth is off")))
            CBManagerStateUnauthorized ->
                powered.complete(Result.failure(IllegalStateException("Bluetooth permission denied")))
            CBManagerStateUnsupported ->
                powered.complete(Result.failure(IllegalStateException("BLE peripheral role unsupported")))
            // Resetting / unknown: transient — keep waiting for a decisive state.
            else -> Unit
        }
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        if (started.isCompleted) return
        if (error == null) {
            started.complete(Result.success(Unit))
        } else {
            started.complete(Result.failure(IllegalStateException(error.localizedDescription)))
            Napier.w("[lab] ios advertising failed: ${error.localizedDescription}")
        }
    }
}
