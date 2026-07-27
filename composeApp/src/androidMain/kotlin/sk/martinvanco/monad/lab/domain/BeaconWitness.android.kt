package sk.martinvanco.monad.lab.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.core.util.ContextProvider
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * Android witness: the existing [BleScanner] is reused rather than a second scanner being opened —
 * two concurrent BLE scans on Android contend for the same radio and halve each other's duty
 * cycle. Identity is pulled from manufacturer data by [IBeaconParser]; zone transitions are
 * synthesised by [ZoneTracker] because Android delivers no region events of its own.
 *
 * Survival while backgrounded comes from the foreground service in [BackgroundResidency]; this
 * class assumes it is already held.
 */
actual class BeaconWitness actual constructor(
    private val scanner: BleScanner,
) {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var tracker: ZoneTracker? = null
    private var expectedUuid: String = ""

    private val _observations = MutableSharedFlow<BeaconObservation>(extraBufferCapacity = 256)
    actual val observations: Flow<BeaconObservation> = _observations.asSharedFlow()

    private val _transitions = MutableSharedFlow<ZoneTransition>(extraBufferCapacity = 64)
    actual val transitions: Flow<ZoneTransition> = _transitions.asSharedFlow()

    private val _isWitnessing = MutableStateFlow(false)
    actual val isWitnessing: Flow<Boolean> = _isWitnessing.asStateFlow()

    actual suspend fun start(plan: BeaconPlan): Result<Unit> {
        if (!plan.isConfigured) {
            return Result.failure(IllegalArgumentException("beacon plan has no UUID"))
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return Result.failure(SecurityException("BLUETOOTH_SCAN / ACCESS_FINE_LOCATION not granted"))
        }

        val started = scanner.startScanning()
        if (started.isFailure) return started

        expectedUuid = plan.uuid.lowercase()
        val zoneTracker = ZoneTracker(plan)
        tracker = zoneTracker

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        _isWitnessing.value = true

        job = scanner.advertisements
            .onEach { advertisement ->
                val manufacturer = advertisement.manufacturerData ?: return@onEach
                for ((companyId, data) in manufacturer) {
                    val parsed = IBeaconParser.parse(companyId, data) ?: continue
                    // Only our anchors. This filter is a privacy control, not an optimisation:
                    // without it a participant's phone becomes a passive collector of every
                    // beacon in a public building.
                    if (!parsed.uuid.equals(expectedUuid, ignoreCase = true)) continue

                    val observation = BeaconObservation(
                        uuid = parsed.uuid,
                        major = parsed.major,
                        minor = parsed.minor,
                        rssi = advertisement.rssi,
                        txPower = parsed.txPower,
                        monotonicNanos = monotonicNanos(),
                        wallMillis = currentTimeMillis(),
                    )
                    _observations.tryEmit(observation)
                    zoneTracker.observe(observation)?.let { _transitions.tryEmit(it) }
                }
            }
            .launchIn(newScope)

        newScope.launch {
            while (_isWitnessing.value) {
                kotlinx.coroutines.delay(SWEEP_INTERVAL_MS)
                zoneTracker.sweep(currentTimeMillis(), monotonicNanos())
                    .forEach { _transitions.tryEmit(it) }
            }
        }

        Napier.i("[lab] android witness started for ${plan.zones.size} anchors")
        return Result.success(Unit)
    }

    actual fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        _isWitnessing.value = false
        tracker?.reset()
        tracker = null
        scanner.stopScanning()
    }

    actual fun residencyDiagnostics(): List<String> = buildList {
        add("scan_permission=${hasPermission(Manifest.permission.BLUETOOTH_SCAN)}")
        add("fine_location=${hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)}")
        add("background_location=${hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}")
        add("transitions=synthesised (Android delivers no region events)")
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            ContextProvider.getContext() as Context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SWEEP_INTERVAL_MS = 5_000L
    }
}
