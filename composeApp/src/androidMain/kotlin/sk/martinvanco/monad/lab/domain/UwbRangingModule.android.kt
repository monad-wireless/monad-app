package sk.martinvanco.monad.lab.domain

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import sk.martinvanco.monad.core.util.ContextProvider
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * Android UWB ranging — decimetre-accurate distance to surveyed anchors.
 *
 * **Why this is worth having.** Every position label in the corpus currently comes from a QR scan:
 * accurate, but discrete and only where someone thought to stick a code. UWB gives a *continuous*
 * range to fixed anchors at roughly 10 cm, which turns "the participant was somewhere near marker
 * A at 14:32" into a trajectory. For fingerprinting that is the difference between labelling a
 * window with a room and labelling it with a position.
 *
 * **The peer problem, stated honestly.** UWB is a two-ended measurement: this module is a
 * *controlee* and requires at least one controller anchor already ranging. With no anchor in the
 * room it produces nothing, and it says so rather than returning an empty log — a range file with
 * zero rows and a successful step is indistinguishable from a session where the participant stood
 * still, and that ambiguity is exactly what ruins a corpus.
 *
 * Output is a TSV keyed on the same monotonic clock as the radio streams, so ranges align with CSI
 * windows without a second synchronisation story.
 */
class UwbRangingModuleAndroid(
    private val context: Context = ContextProvider.getContext(),
) : LabSensorModule {

    override val id: String = "uwb-range"

    override val capability: String = Capability.UWB_RANGING

    override suspend fun probe(): LabSensorModule.Availability {
        if (!context.packageManager.hasSystemFeature("android.hardware.uwb")) {
            return LabSensorModule.Availability.Unsupported("no UWB radio")
        }
        return runCatching {
            // Obtaining a session scope is the only reliable check that the radio is enabled and
            // the app may use it; the feature flag alone survives the user switching UWB off.
            UwbManager.createInstance(context).clientSessionScope()
            LabSensorModule.Availability.Available
        }.getOrElse {
            LabSensorModule.Availability.Unsupported("UWB present but unusable: ${it.message}")
        }
    }

    override suspend fun capture(request: SensorRequest): Result<SensorCapture> {
        val availability = probe()
        if (availability !is LabSensorModule.Availability.Available) {
            val reason = (availability as? LabSensorModule.Availability.Unsupported)?.reason
                ?: "unavailable"
            return Result.failure(IllegalStateException("uwb-range unavailable: $reason"))
        }

        val anchors = request.config?.get("uwb_anchors")?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            }.getOrNull()
        }.orEmpty()

        if (anchors.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "uwb-range needs at least one anchor address in step config 'uwb_anchors'"
                )
            )
        }

        val rows = StringBuilder("mono_ns\twall_ms\tanchor\tdistance_m\tazimuth_deg\televation_deg\n")
        var samples = 0

        val outcome = runCatching {
            val scope = UwbManager.createInstance(context).controleeSessionScope()
            val parameters = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = SESSION_ID,
                subSessionId = 0,
                sessionKeyInfo = STATIC_STS_KEY,
                subSessionKeyInfo = null,
                complexChannel = UwbComplexChannel(CHANNEL, PREAMBLE_INDEX),
                peerDevices = anchors.map { UwbDevice.createForAddress(it) },
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            )

            withTimeoutOrNull(request.timeoutMillis) {
                scope.prepareSession(parameters)
                    .takeWhile { samples < MAX_SAMPLES }
                    .collect { result ->
                        if (result is RangingResult.RangingResultPosition) {
                            val position = result.position
                            val distance = position.distance?.value ?: return@collect
                            rows.append(monotonicNanos()).append('\t')
                                .append(currentTimeMillis()).append('\t')
                                .append(result.device.address.toString()).append('\t')
                                .append(distance).append('\t')
                                .append(position.azimuth?.value?.toString() ?: "").append('\t')
                                .append(position.elevation?.value?.toString() ?: "")
                                .append('\n')
                            samples++
                        }
                    }
            }
        }

        outcome.exceptionOrNull()?.let { error ->
            Napier.w("[lab] uwb-range failed: ${error.message}")
            return Result.failure(error)
        }

        if (samples == 0) {
            // A zero-row range log that reports success is indistinguishable from a stationary
            // participant. Fail instead, so the operator knows the anchor was never heard.
            return Result.failure(
                IllegalStateException(
                    "uwb-range heard no anchor of ${anchors.size} in ${request.timeoutMillis} ms"
                )
            )
        }

        return Result.success(
            SensorCapture(
                moduleId = id,
                summary = mapOf(
                    "samples" to samples.toString(),
                    "anchors" to anchors.joinToString(","),
                ),
                payload = rows.toString().encodeToByteArray(),
                filename = "uwb-range.tsv",
            )
        )
    }

    private companion object {
        /** Fixed so every handset in a session joins the same ranging session as the anchors. */
        const val SESSION_ID = 0x4D4F4E41 // "MONA"
        const val CHANNEL = 9
        const val PREAMBLE_INDEX = 10
        const val MAX_SAMPLES = 20_000

        /**
         * Static STS requires exactly 8 bytes of session key — passing null is rejected at
         * `prepareSession` with "Session key should be 8 bytes in length for static STS", which is
         * what the first run on hardware reported. Fixed and shared, so every phone and anchor in a
         * session derives the same scrambling.
         */
        val STATIC_STS_KEY = byteArrayOf(0x4D, 0x4F, 0x4E, 0x41, 0x44, 0x43, 0x4E, 0x54)
    }
}
