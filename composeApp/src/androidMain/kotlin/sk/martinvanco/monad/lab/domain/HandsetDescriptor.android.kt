package sk.martinvanco.monad.lab.domain

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android descriptor.
 *
 * Android is the platform that can enumerate its sensors: `SensorManager.getSensorList` gives a
 * name, a vendor, a range, a resolution and a minimum delay for every one, which is the sensor
 * reference the dataset wants. The BLE adapter answers the PHY and advertising questions the fleet's
 * passive scan depends on, and `WifiManager` says which bands and standards the radio supports.
 *
 * Every probe is wrapped: a missing system service or a refused permission yields an absent field,
 * never a failed quest start. Permission-gated readings (Bluetooth on API 31+) are attempted and
 * dropped on `SecurityException`, which is the honest outcome — the phone did not say.
 */
actual suspend fun describeHandset(handsetId: String): HandsetDescriptor {
    val capabilities = detectCapabilities()
    val context = ContextProvider.getContext()

    val sensors = runCatching {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            SensorFact(
                kind = sensorKind(sensor.type),
                name = sensor.name,
                vendor = sensor.vendor,
                version = sensor.version,
                maxRange = sensor.maximumRange,
                resolution = sensor.resolution,
                minDelayUs = sensor.minDelay,
                powerMa = sensor.power,
                platformType = sensor.type,
            )
        }
    }.getOrDefault(emptyList())

    val ble = runCatching {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return@runCatching RadioFacts()
        RadioFacts(
            le2mPhy = adapter.isLe2MPhySupported,
            leCodedPhy = adapter.isLeCodedPhySupported,
            leExtendedAdvertising = adapter.isLeExtendedAdvertisingSupported,
            multipleAdvertisement = adapter.isMultipleAdvertisementSupported,
            maxAdvertisingDataLength = adapter.leMaximumAdvertisingDataLength,
        )
    }.getOrDefault(RadioFacts())
    val radio = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        ble.copy(
            wifi5GHz = wifi.is5GHzBandSupported,
            wifi6GHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifi.is6GHzBandSupported else null,
            wifi11ax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) else null,
        )
    }.getOrDefault(ble)

    val power = runCatching { context.getSystemService(Context.POWER_SERVICE) as PowerManager }.getOrNull()
    val state = HandsetState(
        thermal = power?.let { pm -> runCatching { thermalName(pm.currentThermalStatus) }.getOrNull() },
        lowPowerMode = power?.let { pm -> runCatching { pm.isPowerSaveMode }.getOrNull() },
        batteryPct = runCatching {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull(),
    )

    return HandsetDescriptor(
        handsetId = handsetId,
        platform = "android",
        machine = Build.DEVICE?.takeIf { it.isNotBlank() },
        manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
        model = Build.MODEL?.takeIf { it.isNotBlank() },
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null,
        osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
        osBuild = listOfNotNull(Build.ID?.takeIf { it.isNotBlank() }, Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() },
        appVersion = AppConfig.APP_VERSION,
        buildId = AppConfig.BUILD_ID,
        capabilities = capabilities.capabilities.sorted(),
        sensors = sensors,
        radio = radio,
        state = state,
    )
}

/** The iOS vocabulary, so one column reads across platforms. Android's finer steps fold into it. */
private fun thermalName(status: Int): String? = when (status) {
    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
    PowerManager.THERMAL_STATUS_MODERATE -> "fair"
    PowerManager.THERMAL_STATUS_SEVERE -> "serious"
    PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
    else -> null
}

/** A name for the common types; the numeric type rides along in `platform_type` for the rest. */
private fun sensorKind(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER -> "accelerometer"
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accelerometer_uncalibrated"
    Sensor.TYPE_GYROSCOPE -> "gyroscope"
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope_uncalibrated"
    Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "magnetometer_uncalibrated"
    Sensor.TYPE_PRESSURE -> "barometer"
    Sensor.TYPE_GRAVITY -> "gravity"
    Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
    Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
    Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "geomagnetic_rotation_vector"
    Sensor.TYPE_LIGHT -> "light"
    Sensor.TYPE_PROXIMITY -> "proximity"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
    Sensor.TYPE_RELATIVE_HUMIDITY -> "relative_humidity"
    Sensor.TYPE_STEP_COUNTER -> "step_counter"
    Sensor.TYPE_STEP_DETECTOR -> "step_detector"
    Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
    Sensor.TYPE_HEART_RATE -> "heart_rate"
    Sensor.TYPE_POSE_6DOF -> "pose_6dof"
    Sensor.TYPE_STATIONARY_DETECT -> "stationary_detect"
    Sensor.TYPE_MOTION_DETECT -> "motion_detect"
    Sensor.TYPE_HINGE_ANGLE -> "hinge_angle"
    else -> "type_$type"
}
