package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLBeacon
import platform.CoreLocation.CLBeaconIdentityConstraint
import platform.CoreLocation.CLBeaconRegion
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLProximity
import platform.CoreLocation.CLRegion
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * iOS witness — CoreLocation, not CoreBluetooth.
 *
 * The [scanner] parameter is accepted for a uniform construction signature and deliberately
 * unused: iOS does **not** surface iBeacon frames through CoreBluetooth at all (Apple-company
 * manufacturer data is filtered out), and a backgrounded CoreBluetooth scan additionally requires
 * an explicit service-UUID filter and coalesces duplicates. CoreLocation is the only path that
 * works, and it is also the better one — `startMonitoring` delivers region enter/exit **even after
 * the app has been terminated**, because the system relaunches the app to hand it the event.
 *
 * That is why region crossings, not RSSI, are the ground-truth primitive in this design: they are
 * the only signal that survives the condition every field session actually runs in.
 */
@OptIn(ExperimentalForeignApi::class)
actual class BeaconWitness actual constructor(
    @Suppress("unused") private val scanner: BleScanner,
) {
    private val manager = CLLocationManager()
    private var delegate: BeaconDelegate? = null
    private var plan: BeaconPlan = BeaconPlan()
    private val monitoredRegions = mutableListOf<CLBeaconRegion>()

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
        val uuid = NSUUID(uUIDString = plan.uuid)
        this.plan = plan

        if (!CLLocationManager.isMonitoringAvailableForClass(CLBeaconRegion.`class`()!!)) {
            return Result.failure(IllegalStateException("beacon region monitoring unavailable"))
        }

        val status = CLLocationManager.authorizationStatus()
        if (status != kCLAuthorizationStatusAuthorizedAlways) {
            // "When In Use" is not enough: it is revoked the moment the app leaves the foreground,
            // which is the only state that matters here.
            manager.requestAlwaysAuthorization()
        }

        val handler = BeaconDelegate(
            plan = plan,
            onBeacons = { beacons -> beacons.forEach { emit(it) } },
            onTransition = { region, entered -> emitTransition(region, entered) },
        )
        delegate = handler
        manager.delegate = handler
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false

        // One region per major where majors are declared, otherwise a single UUID-wide region.
        // iOS caps an app at 20 monitored regions, so grouping by major is what keeps a
        // many-anchor deployment inside the budget — the individual anchor is resolved by ranging.
        monitoredRegions.clear()
        val majors = plan.majors.ifEmpty { listOf(null) }
        majors.take(MAX_REGIONS).forEach { major ->
            val region = if (major == null) {
                CLBeaconRegion(uUID = uuid, identifier = "monad-${plan.uuid}")
            } else {
                CLBeaconRegion(uUID = uuid, major = major.toUShort(), identifier = "monad-$major")
            }
            region.notifyEntryStateOnDisplay = false
            region.notifyOnEntry = true
            region.notifyOnExit = true
            monitoredRegions += region
            manager.startMonitoringForRegion(region)
            manager.startRangingBeaconsSatisfyingConstraint(
                if (major == null) {
                    CLBeaconIdentityConstraint(uUID = uuid)
                } else {
                    CLBeaconIdentityConstraint(uUID = uuid, major = major.toUShort())
                }
            )
        }

        _isWitnessing.value = true
        Napier.i("[lab] ios witness started, ${monitoredRegions.size} regions, ${plan.zones.size} anchors")
        return Result.success(Unit)
    }

    actual fun stop() {
        monitoredRegions.forEach { manager.stopMonitoringForRegion(it) }
        monitoredRegions.clear()
        manager.allowsBackgroundLocationUpdates = false
        manager.delegate = null
        delegate = null
        _isWitnessing.value = false
    }

    actual fun residencyDiagnostics(): List<String> {
        val status = CLLocationManager.authorizationStatus()
        return listOf(
            "authorization=${describeAuthorization(status)}",
            "always_required=true (When In Use is revoked on backgrounding)",
            "monitoring_available=${CLLocationManager.isMonitoringAvailableForClass(CLBeaconRegion.`class`()!!)}",
            "ranging_available=${CLLocationManager.isRangingAvailable()}",
            "regions=${monitoredRegions.size}/$MAX_REGIONS",
            "relaunch_on_region_event=yes (survives app termination)",
        )
    }

    private fun emit(beacon: CLBeacon) {
        val observation = BeaconObservation(
            uuid = beacon.UUID.UUIDString.lowercase(),
            major = beacon.major.intValue,
            minor = beacon.minor.intValue,
            rssi = beacon.rssi.toInt(),
            // CoreLocation does not expose the advertised measured power; it gives a derived
            // accuracy instead, which is what the console shows.
            txPower = null,
            monotonicNanos = monotonicNanos(),
            wallMillis = currentTimeMillis(),
            proximity = describeProximity(beacon.proximity),
            accuracyMetres = beacon.accuracy.takeIf { it >= 0 },
        )
        _observations.tryEmit(observation)
    }

    private fun emitTransition(region: CLBeaconRegion, entered: Boolean) {
        val major = region.major?.intValue ?: -1
        val minor = region.minor?.intValue ?: -1
        _transitions.tryEmit(
            ZoneTransition(
                zone = plan.zone(major, minor),
                major = major,
                minor = minor,
                entered = entered,
                monotonicNanos = monotonicNanos(),
                wallMillis = currentTimeMillis(),
            )
        )
    }

    private fun describeProximity(proximity: CLProximity): String = when (proximity) {
        CLProximity.CLProximityImmediate -> "immediate"
        CLProximity.CLProximityNear -> "near"
        CLProximity.CLProximityFar -> "far"
        else -> "unknown"
    }

    private fun describeAuthorization(status: CLAuthorizationStatus): String = when (status) {
        kCLAuthorizationStatusAuthorizedAlways -> "always"
        else -> "NOT always (background witnessing will stop)"
    }

    private companion object {
        /** iOS hard limit on monitored regions per app. */
        const val MAX_REGIONS = 20
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BeaconDelegate(
    private val plan: BeaconPlan,
    private val onBeacons: (List<CLBeacon>) -> Unit,
    private val onTransition: (CLBeaconRegion, Boolean) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(
        manager: CLLocationManager,
        didRangeBeacons: List<*>,
        inRegion: CLBeaconRegion,
    ) {
        val beacons = didRangeBeacons.filterIsInstance<CLBeacon>()
            .filter { it.UUID.UUIDString.equals(plan.uuid, ignoreCase = true) }
        if (beacons.isNotEmpty()) onBeacons(beacons)
    }

    // Both selectors map to the same Kotlin signature (they differ only in parameter name), so
    // the interop needs to be told these are distinct ObjC methods rather than an overload clash.
    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) {
        (didEnterRegion as? CLBeaconRegion)?.let { onTransition(it, true) }
    }

    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didExitRegion: CLRegion) {
        (didExitRegion as? CLBeaconRegion)?.let { onTransition(it, false) }
    }

    override fun locationManager(manager: CLLocationManager, monitoringDidFailForRegion: CLRegion?, withError: NSError) {
        Napier.w("[lab] region monitoring failed: ${withError.localizedDescription}")
    }
}
