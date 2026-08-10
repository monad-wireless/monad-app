package sk.martinvanco.monad.lab.domain

/**
 * The **people** channel.
 *
 * Every other stream this app records is a phone stream: the illuminator's paced datagrams, the
 * witness's beacon observations, the zone transitions derived from them. All of them count
 * *phones*. The science this instrument feeds — CSI crowd counting calibrated by BLE — needs
 * *people*, and the difference between the two is not noise: phone-vs-person bias is itself the
 * quantity a later experiment sets out to measure.
 *
 * A truth channel that could be derived from phone presence would therefore be worthless. This one
 * cannot be: it only ever advances when a human deliberately points a camera at a printed code.
 * No beacon observation, no RSSI threshold, and no dwell heuristic can manufacture one of these.
 *
 * The identity carried is the same opaque participant pseudonym the session sidecar uses — the
 * count-without-identify posture. The dataset learns that *someone* entered zone `q2-lab-a`; it
 * never learns who.
 */
data class GroundTruthEvent(
    /**
     * The lab session this scan is truth *for* — taken from the scanned code, not from whatever
     * this phone happens to be recording. One session, many participants, many phones.
     */
    val labSessionId: String,
    /** Opaque participant pseudonym. Never a name, never the account e-mail. */
    val participantToken: String,
    /** PostGIS cell id of the zone whose entrance carries the code. */
    val zoneId: String,
    val direction: GroundTruthDirection,
    val site: String,
    /**
     * Device monotonic nanoseconds — the same sleep-continuous clock every other sample stream is
     * stamped with, and the column the analysis side joins on. See [monotonicNanos].
     */
    val monotonicNanos: Long,
    /** Unix epoch milliseconds, carried alongside exactly as the other streams carry it. */
    val wallMillis: Long,
    /**
     * Client-generated idempotency key.
     *
     * Ground truth is re-uploaded whole on every flush (see `LabSessionUploader.flushGroundTruth`),
     * and a participant who taps twice, or a scanner that fires twice on one code, must not become
     * two people. The nonce is what lets both this device and the collection side de-duplicate
     * without guessing from timestamps.
     */
    val scanNonce: String,
    /**
     * The local recording session that was open when the scan happened, if any. Provenance only —
     * a scan is valid whether or not this phone was instrumenting at the time, which is the point:
     * a participant arriving before the operator starts the run is still a person in the room.
     */
    val recordingSessionId: String?,
)

/** Which way across the boundary. Recorded resolved — `toggle` never reaches storage. */
enum class GroundTruthDirection {
    IN,
    OUT,
    ;

    val wire: String get() = name.lowercase()

    /** The state this scan flips to when the code says "toggle". */
    val opposite: GroundTruthDirection get() = if (this == IN) OUT else IN

    companion object {
        fun fromWire(value: String?): GroundTruthDirection? = when (value?.lowercase()) {
            "in" -> IN
            "out" -> OUT
            else -> null
        }
    }
}

/**
 * What a session/zone QR code carries.
 *
 * Deliberately *not* a participant code: the phone already knows which participant it belongs to,
 * and putting an identity into a code that gets printed and taped to a doorframe would be the one
 * way to turn a pseudonymous dataset into a personal one.
 *
 * [declaredDirection] null means the code is a **toggle**: one code per doorway, and the app
 * resolves in/out from the participant's own last scan for that zone. A lab that prefers two
 * separate codes (one on each side of a turnstile) simply issues them with `dir=in` / `dir=out`
 * and gets no state-dependence at all.
 */
data class GroundTruthTicket(
    val labSessionId: String,
    val zoneId: String,
    val site: String = "",
    val declaredDirection: GroundTruthDirection? = null,
) {
    val isValid: Boolean get() = labSessionId.isNotBlank() && zoneId.isNotBlank()
}

/**
 * The wire format of the printed code, and its parser.
 *
 * A URI rather than JSON: it survives being read aloud, pasted into a chat, or typed into a lab
 * notebook, and a QR of it stays low-density enough to scan from across a doorway. The `/v1`
 * segment is load-bearing — a code printed today must be rejectable, not silently misread, by a
 * build that has moved on.
 *
 * Kept pure (no clock, no I/O) so both ends of the round trip are testable without a camera.
 */
object GroundTruthQr {

    const val SCHEME: String = "monad://ground-truth/"
    const val VERSION: String = "v1"
    const val PREFIX: String = SCHEME + VERSION + "?"

    fun encode(ticket: GroundTruthTicket): String = buildString {
        append(PREFIX)
        append("session=").append(escape(ticket.labSessionId))
        append("&zone=").append(escape(ticket.zoneId))
        if (ticket.site.isNotBlank()) append("&site=").append(escape(ticket.site))
        append("&dir=").append(ticket.declaredDirection?.wire ?: TOGGLE)
    }

    /**
     * Parse a scanned string into a classified outcome.
     *
     * A classification rather than a nullable ticket, because the four ways a scan fails need four
     * different sentences in a doorway. "That is not a check-in code" is right for a shop receipt
     * and actively misleading for a code printed by a newer build of this app, which needs "update
     * the app" instead. A single null cannot carry that difference, and the participant standing
     * there cannot debug it.
     */
    fun parse(raw: String): QrScan {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(SCHEME)) return QrScan.NotOurCode
        val remainder = trimmed.removePrefix(SCHEME)
        val version = remainder.substringBefore('?', missingDelimiterValue = remainder)
        if (version != VERSION) return QrScan.UnsupportedVersion(version)
        if (!trimmed.startsWith(PREFIX)) return QrScan.Malformed("the code has no parameters")

        val params = mutableMapOf<String, String>()
        trimmed.removePrefix(PREFIX)
            .split('&')
            .forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val index = pair.indexOf('=')
                if (index <= 0) return@forEach
                params[pair.substring(0, index)] = unescape(pair.substring(index + 1))
            }
        val direction = params["dir"]
        // An unrecognised direction is rejected rather than defaulted: a typo'd code that silently
        // becomes a toggle would corrupt a whole session's counts in a way nobody notices until the
        // data is analysed.
        if (direction != null && direction != TOGGLE && GroundTruthDirection.fromWire(direction) == null) {
            return QrScan.Malformed("the code asks for direction '$direction', which is not in/out/toggle")
        }
        val ticket = GroundTruthTicket(
            labSessionId = params["session"].orEmpty(),
            zoneId = params["zone"].orEmpty(),
            site = params["site"].orEmpty(),
            declaredDirection = GroundTruthDirection.fromWire(direction),
        )
        if (!ticket.isValid) {
            return QrScan.Malformed(
                if (ticket.labSessionId.isBlank()) "the code names no lab session"
                else "the code names no zone"
            )
        }
        return QrScan.Ok(ticket)
    }

    private const val TOGGLE = "toggle"

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private fun escape(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && UNRESERVED.indexOf(char) >= 0) {
                append(char)
            } else {
                val unsigned = byte.toInt() and 0xFF
                append('%')
                append(HEX[unsigned shr 4])
                append(HEX[unsigned and 0x0F])
            }
        }
    }

    private fun unescape(value: String): String {
        if (value.indexOf('%') < 0) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                    continue
                }
            }
            bytes += char.code.toByte()
            i += 1
        }
        return bytes.toByteArray().decodeToString()
    }

    private val HEX = "0123456789ABCDEF"
}

/**
 * What a camera handed us.
 *
 * Every branch carries the sentence a participant should read. The camera will happily decode a
 * Wi-Fi config, a URL, or a shop receipt, and none of those are people.
 */
sealed interface QrScan {
    data class Ok(val ticket: GroundTruthTicket) : QrScan

    /** Not a MonadCount code at all. */
    data object NotOurCode : QrScan

    /**
     * A MonadCount code from a different protocol version. The `/v1` segment is load-bearing: a
     * code printed for a build that has moved on must be **rejected**, not silently misread into
     * a plausible-looking but wrong ticket.
     */
    data class UnsupportedVersion(val version: String) : QrScan

    /** Ours, right version, but it does not carry what it must. */
    data class Malformed(val reason: String) : QrScan

    /** The message to show, in words a participant can act on. */
    val message: String
        get() = when (this) {
            is Ok -> ""
            NotOurCode -> "That is not a MonadCount check-in code. Look for the code on the " +
                "doorframe of the zone you are entering."
            is UnsupportedVersion -> "This code was printed for a different version of the app " +
                "(got '$version', this build reads '${GroundTruthQr.VERSION}'). Ask the operator " +
                "for the current code, or update the app."
            is Malformed -> "That check-in code is damaged — $reason. Ask the operator to reprint it."
        }
}
