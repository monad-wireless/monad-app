package sk.martinvanco.monad.lab.domain

import kotlin.math.pow

/**
 * An iBeacon observation: the anchor's identity plus the RSSI it was heard at.
 *
 * Anchors advertise as iBeacons rather than under a device name because that is the only anchor
 * format a **backgrounded or terminated** iOS app can be woken by. iOS surfaces them through
 * CoreLocation (not CoreBluetooth, which never sees Apple-company manufacturer data); Android sees
 * them as ordinary BLE manufacturer data and parses them with [IBeaconParser].
 */
data class BeaconObservation(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    /** Calibrated 1 m reference power from the advertisement, dBm. */
    val txPower: Int?,
    val monotonicNanos: Long,
    val wallMillis: Long,
    /** Platform-reported proximity bucket, where available (`immediate`/`near`/`far`/`unknown`). */
    val proximity: String = "unknown",
    /** Platform-computed distance estimate in metres, where available. */
    val accuracyMetres: Double? = null,
) {
    val anchorKey: String get() = "$major/$minor"

    /**
     * Log-distance range estimate, for the debug console only.
     *
     * This is deliberately *not* used for anything load-bearing. RSSI-to-distance inversion in an
     * indoor multipath environment carries several metres of error, which is why the design uses
     * beacon *regions* as the truth primitive and treats RSSI as a coarse interpolant between
     * them. Path-loss exponent defaults to 2.5, mid-range of the n ∈ [1.8, 3.5] envelope reported
     * for indoor BLE.
     */
    fun estimateRangeMetres(pathLossExponent: Double = 2.5): Double? {
        val reference = txPower ?: return null
        if (rssi == 0) return null
        return 10.0.pow((reference - rssi) / (10.0 * pathLossExponent))
    }
}

/** A zone transition — the anchor of the ground-truth chain. */
data class ZoneTransition(
    val zone: BeaconZone?,
    val major: Int,
    val minor: Int,
    val entered: Boolean,
    val monotonicNanos: Long,
    val wallMillis: Long,
)

/**
 * Parses the Apple iBeacon layout out of BLE manufacturer data. Used on the Android path and in
 * the debug console; on iOS CoreLocation does this for us.
 *
 * Manufacturer-data layout after the 2-byte company identifier (0x004C, little-endian on the
 * wire):
 *
 * ```
 * 0      1   0x02  beacon type
 * 1      1   0x15  remaining length (21)
 * 2     16   proximity UUID
 * 18     2   major (big-endian)
 * 20     2   minor (big-endian)
 * 22     1   measured power at 1 m (signed)
 * ```
 */
object IBeaconParser {

    const val APPLE_COMPANY_ID: Int = 0x004C
    private const val TYPE_PROXIMITY: Int = 0x02
    private const val PAYLOAD_LENGTH: Int = 0x15
    private const val EXPECTED_SIZE: Int = 23

    /**
     * @param companyId the manufacturer-data company identifier as reported by the scanner
     * @param data manufacturer-data bytes **excluding** the company identifier
     */
    fun parse(companyId: Int, data: ByteArray): ParsedBeacon? {
        if (companyId != APPLE_COMPANY_ID) return null
        if (data.size < EXPECTED_SIZE) return null
        if ((data[0].toInt() and 0xFF) != TYPE_PROXIMITY) return null
        if ((data[1].toInt() and 0xFF) != PAYLOAD_LENGTH) return null

        val uuid = formatUuid(data.copyOfRange(2, 18))
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val txPower = data[22].toInt() // signed
        return ParsedBeacon(uuid, major, minor, txPower)
    }

    /** Render 16 raw bytes as a canonical lowercase UUID string. */
    fun formatUuid(bytes: ByteArray): String {
        require(bytes.size == 16) { "uuid must be 16 bytes" }
        val hex = buildString(32) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** Parse a canonical UUID string back to 16 raw bytes; null when malformed. */
    fun parseUuid(text: String): ByteArray? {
        val hex = text.filter { it != '-' }.lowercase()
        if (hex.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = HEX.indexOf(hex[i * 2])
            val lo = HEX.indexOf(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"

    data class ParsedBeacon(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val txPower: Int,
    )
}
