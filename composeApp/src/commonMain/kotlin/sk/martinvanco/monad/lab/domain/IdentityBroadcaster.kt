package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The broadcaster role: advertise this session's identity as a plain BLE frame so the fleet's
 * passive scan can observe the handset it belongs to.
 *
 * The frame is deliberately **not** an iBeacon. iOS strips manufacturer data from
 * `startAdvertising`, so an iPhone cannot emit an iBeacon at all; the one shape every platform can
 * emit — Android, iOS in the foreground, an ESP32 — and every raw HCI scanner can read is a legacy
 * advertisement carrying a single **128-bit service UUID**. Identity therefore lives inside the
 * UUID itself (see [AdvertiseIdentity]): the first twelve bytes are the deployment's namespace,
 * the last four are the participant and session keys.
 *
 * Platform truth, stated once so nobody designs against it:
 *
 * - **iOS** foregrounded honours service UUIDs and a local name; nothing else. **Backgrounded**,
 *   the UUID moves into Apple's proprietary overflow area, which a raw scanner cannot read — so a
 *   backgrounded iOS broadcast is invisible to the fleet, and [start] reports `foregroundOnly`.
 *   The interval is system-controlled and cannot be commanded.
 * - **Android** honours the full request, but rounds the interval onto its three advertising
 *   buckets. The accepted bucket is reported, because the commanded value is a request and the
 *   dose analysis needs what actually went on air.
 *
 * Like every role, refusal must be loud: a broadcast nobody can hear is worse than none.
 */
expect class IdentityBroadcaster() {

    val isBroadcasting: Flow<Boolean>

    /**
     * Begin advertising [request]. Returns what the platform actually accepted, or a failure when
     * it refuses — missing advertise permission, Bluetooth off, or no advertiser on this hardware.
     */
    suspend fun start(request: BroadcastRequest): Result<BroadcastReport>

    fun stop()

    /** Platform posture, surfaced in the lab console so a refusal is visible before a session. */
    fun diagnostics(): List<String>
}

/** What the caller asks the platform to put on air. */
data class BroadcastRequest(
    /** The full 128-bit service UUID, already carrying the identity bytes. */
    val serviceUuid: String,
    /** Commanded advertising interval. Android rounds it onto a bucket; iOS ignores it. */
    val intervalMs: Int = 250,
    /** `ultra_low` | `low` | `medium` | `high`. Android only; iOS ignores it. */
    val txPower: String = "medium",
)

/**
 * What actually went on air. Written into the session sidecar and onto the marker that opens the
 * broadcast, so the analysis reads the accepted values, never the commanded ones.
 */
@Serializable
data class BroadcastReport(
    @SerialName("service_uuid") val serviceUuid: String,
    @SerialName("requested_interval_ms") val requestedIntervalMs: Int,
    /** The platform's answer: an Android bucket ("balanced (~250 ms)") or "system-controlled". */
    @SerialName("accepted_interval") val acceptedInterval: String,
    @SerialName("tx_power") val txPower: String,
    /** True when backgrounding would take the frame off the air the fleet can read. */
    @SerialName("foreground_only") val foregroundOnly: Boolean,
    val notes: List<String> = emptyList(),
)

/**
 * Derives the advertised service UUID from the lab bundle's namespace and the session identity.
 *
 * Layout: bytes 0–11 come from the bundle's `advertise.namespace_uuid` verbatim — that prefix is
 * what the fleet's scanner matches on. Byte 12–13 carry a 16-bit participant key, bytes 14–15 a
 * 16-bit session key, both FNV-1a hashes folded to 16 bits.
 *
 * The keys are pseudonyms, not security: they let the analysis separate two handsets in one room
 * and join a frame to a session sidecar, and the sidecar records the full mapping. Sixteen bits
 * per key is enough for a lab cohort and keeps the whole identity inside one advertisement.
 */
object AdvertiseIdentity {

    /** Builds the UUID to advertise, or null when the namespace is absent or malformed. */
    fun serviceUuid(namespaceUuid: String, participantId: String, sessionId: String): String? {
        val bytes = parseUuid(namespaceUuid) ?: return null
        val participantKey = fold16(participantId)
        val sessionKey = fold16(sessionId)
        bytes[12] = (participantKey shr 8).toByte()
        bytes[13] = participantKey.toByte()
        bytes[14] = (sessionKey shr 8).toByte()
        bytes[15] = sessionKey.toByte()
        return formatUuid(bytes)
    }

    /** The 16-bit pseudonym key for one identifier; recorded in the sidecar beside the UUID. */
    fun fold16(value: String): Int {
        var hash = FNV_OFFSET
        for (byte in value.encodeToByteArray()) {
            hash = (hash xor (byte.toInt() and 0xFF)) * FNV_PRIME
        }
        return (hash xor (hash ushr 16)) and 0xFFFF
    }

    fun parseUuid(uuid: String): ByteArray? {
        val hex = uuid.replace("-", "").lowercase()
        if (hex.length != 32 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(16) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun formatUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private const val FNV_OFFSET = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193
}
