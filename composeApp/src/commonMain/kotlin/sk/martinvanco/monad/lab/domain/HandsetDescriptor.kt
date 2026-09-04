@file:OptIn(ExperimentalSerializationApi::class)

package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The phone describing itself, once, at the moment a quest starts (IP-149).
 *
 * One value, three consumers: the body of `POST /api/quest/{id}/start` (the backend freezes it on
 * the enrollment as measurement provenance), the sidecar's `environment.handset` block (so the
 * dataset on S3 is self-describing without the backend), and the operator's admin, which renders
 * every key below as a table.
 *
 * THREE RULES, and each is a line of code somewhere in this file:
 *
 * 1. **Unknown is absent, never a placeholder.** Every field the platform may not be able to
 *    answer is nullable, defaults to null, and carries `@EncodeDefault(NEVER)` — so it is omitted
 *    from the wire whatever `Json` instance encodes it, including the sidecar's `encodeDefaults =
 *    true`. An empty [radio] on iOS is a statement that iOS publishes no BLE or Wi-Fi capability
 *    API, and the admin prints it as such. Nothing here invents a default reading.
 * 2. **The token set stays the contract.** [capabilities] is the same list `GET /api/quests`
 *    already receives. The descriptor adds evidence around it; it does not change what a quest
 *    requires.
 * 3. **The app owns the identity of the installation.** [handsetId] is generated once and kept in
 *    the settings store — never `identifierForVendor` or `ANDROID_ID`. A reinstall is a new
 *    handset, which is honest: local state, granted permissions and the clock epoch are new too.
 *    See [HandsetIdentity].
 *
 * The backend validates a CLOSED set of top-level keys. Adding a field here without adding it
 * there turns every start into a 400 (`VALIDATION_108`), which is the loud failure we want: a
 * field the admin does not know how to render must not be stored silently.
 */
@Serializable
data class HandsetDescriptor(
    @SerialName("handset_id") val handsetId: String,
    /** `ios` | `android`. */
    val platform: String,
    /** The hardware identifier the platform publishes: `iPhone15,2`, `a54x`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val machine: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val manufacturer: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val model: String? = null,
    /** Android `Build.SOC_MODEL` (API 31+). iOS publishes none. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val soc: String? = null,
    @SerialName("os_version") @EncodeDefault(EncodeDefault.Mode.NEVER) val osVersion: String? = null,
    /** iOS `kern.osversion` (`22G86`); Android `Build.ID` plus the security patch level. */
    @SerialName("os_build") @EncodeDefault(EncodeDefault.Mode.NEVER) val osBuild: String? = null,
    @SerialName("app_version") @EncodeDefault(EncodeDefault.Mode.NEVER) val appVersion: String? = null,
    @SerialName("build_id") @EncodeDefault(EncodeDefault.Mode.NEVER) val buildId: String? = null,
    /** The capability tokens the quest filter consumes; see [Capability]. Sorted for stable bytes. */
    val capabilities: List<String> = emptyList(),
    /** What the platform says about each sensor. Platform-shaped by design; see [SensorFact]. */
    val sensors: List<SensorFact> = emptyList(),
    /** BLE and Wi-Fi capability flags. Android answers; iOS publishes nothing and sends `{}`. */
    val radio: RadioFacts = RadioFacts(),
    /** The run's condition at start — the one block that is about this moment, not this phone. */
    val state: HandsetState = HandsetState(),
) {
    companion object {
        /** The encoder the start request uses. The sidecar uses the instrument's own; both omit nulls (rule 1). */
        val json: Json = Json { encodeDefaults = true }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}

/**
 * One sensor as the platform describes it.
 *
 * Android's `SensorManager.getSensorList` gives a name, a vendor, a range, a resolution and a
 * minimum delay per sensor. iOS gives availability flags per subsystem and nothing else, so an iOS
 * entry is `{kind, available}` and every other field is absent. Both are honest; neither is padded.
 */
@Serializable
data class SensorFact(
    /** `accelerometer`, `gyroscope`, `magnetometer`, `barometer`, `heading`, `uwb`, `lidar_mesh`, … */
    val kind: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val available: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vendor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val version: Int? = null,
    @SerialName("max_range") @EncodeDefault(EncodeDefault.Mode.NEVER) val maxRange: Float? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolution: Float? = null,
    /** Fastest sampling the platform allows, in microseconds. */
    @SerialName("min_delay_us") @EncodeDefault(EncodeDefault.Mode.NEVER) val minDelayUs: Int? = null,
    @SerialName("power_ma") @EncodeDefault(EncodeDefault.Mode.NEVER) val powerMa: Float? = null,
    /** Android's numeric `Sensor.getType()`, for the kinds this list does not name. */
    @SerialName("platform_type") @EncodeDefault(EncodeDefault.Mode.NEVER) val platformType: Int? = null,
)

/** Radio capability flags. Every field nullable: an unanswered question is absent, not `false`. */
@Serializable
data class RadioFacts(
    @SerialName("le_2m_phy") @EncodeDefault(EncodeDefault.Mode.NEVER) val le2mPhy: Boolean? = null,
    @SerialName("le_coded_phy") @EncodeDefault(EncodeDefault.Mode.NEVER) val leCodedPhy: Boolean? = null,
    @SerialName("le_extended_advertising") @EncodeDefault(EncodeDefault.Mode.NEVER) val leExtendedAdvertising: Boolean? = null,
    @SerialName("multiple_advertisement") @EncodeDefault(EncodeDefault.Mode.NEVER) val multipleAdvertisement: Boolean? = null,
    @SerialName("max_advertising_data_length") @EncodeDefault(EncodeDefault.Mode.NEVER) val maxAdvertisingDataLength: Int? = null,
    @SerialName("wifi_5ghz") @EncodeDefault(EncodeDefault.Mode.NEVER) val wifi5GHz: Boolean? = null,
    @SerialName("wifi_6ghz") @EncodeDefault(EncodeDefault.Mode.NEVER) val wifi6GHz: Boolean? = null,
    @SerialName("wifi_11ax") @EncodeDefault(EncodeDefault.Mode.NEVER) val wifi11ax: Boolean? = null,
)

/** The phone's condition at the instant of the start request. Drifts within a run; recorded per run. */
@Serializable
data class HandsetState(
    /** `nominal` | `fair` | `serious` | `critical` (iOS names; Android's status is mapped onto them). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val thermal: String? = null,
    @SerialName("low_power_mode") @EncodeDefault(EncodeDefault.Mode.NEVER) val lowPowerMode: Boolean? = null,
    @SerialName("battery_pct") @EncodeDefault(EncodeDefault.Mode.NEVER) val batteryPct: Int? = null,
)

/**
 * Describe this handset. Implemented per platform because the answers come from different places:
 * `utsname` and CoreMotion on iOS, `Build` and `SensorManager` on Android. Both compose
 * [detectCapabilities] for the token set so the two can never disagree.
 *
 * Suspending for the reason [detectCapabilities] is: a module probe may hop to the main looper.
 * A platform call that throws yields an absent field, never a failed start — the descriptor is
 * evidence about the run, and evidence must not be able to stop the run.
 */
expect suspend fun describeHandset(handsetId: String): HandsetDescriptor
