package sk.martinvanco.monad.lab.domain

/**
 * Wire format for the phone→collector UDP stream ("MNDP v1").
 *
 * One format carries both jobs on purpose. Every data packet already contains the phone's
 * monotonic timestamp and a sequence number, so the collector's arrival stamps make clock offset
 * and delivered rate continuously observable from the data stream itself — the periodic time
 * exchanges only bootstrap and bound the estimate.
 *
 * Header is fixed at [HEADER_SIZE] bytes, big-endian (network order):
 *
 * ```
 *  0  4  magic  "MNDP"
 *  4  1  version
 *  5  1  type          1 = DATA, 2 = TIME_REQUEST, 3 = TIME_RESPONSE, 4 = SESSION_HELLO
 *  6  2  flags
 *  8 16  session id    raw UUID bytes
 * 24  4  sequence
 * 28  8  t_mono_ns     sender monotonic clock, sleep-continuous
 * 36  8  t_wall_ms     sender wall clock, recorded but never load-bearing
 * 44  2  payload len
 * 46  2  reserved
 * ```
 *
 * A TIME_RESPONSE appends the collector's two reference stamps (t2 = receive, t3 = send) as two
 * big-endian u64 in the payload, which is what makes the four-timestamp estimator possible.
 */
object LabPacket {

    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 48
    const val TIME_PAYLOAD_SIZE: Int = 16

    val MAGIC: ByteArray = byteArrayOf(0x4D, 0x4E, 0x44, 0x50) // "MNDP"

    const val TYPE_DATA: Int = 1
    const val TYPE_TIME_REQUEST: Int = 2
    const val TYPE_TIME_RESPONSE: Int = 3

    /**
     * Session announcement, sent once at session start and repeated with every clock burst.
     *
     * Data packets carry only a session UUID, but the collector files a session under
     * `<participant>/<session>/` so that the phone's own artefacts and the receiver's view of the
     * same link land in one prefix. Widening every packet at 200 Hz to carry a participant id
     * would cost far more than repeating a small announcement — and repeating it is what lets a
     * collector that started late, or that missed the first datagram on a contended channel, still
     * learn who it is talking to.
     */
    const val TYPE_SESSION_HELLO: Int = 4

    /**
     * Build a packet. [payloadBytes] pads the datagram out to a commanded size so that frame
     * length — which changes the airtime a frame occupies — is an experiment variable rather than
     * an accident of the payload.
     */
    fun encode(
        type: Int,
        sessionId: ByteArray,
        sequence: Int,
        monotonicNanos: Long,
        wallMillis: Long,
        payloadBytes: Int = 0,
        payload: ByteArray? = null,
    ): ByteArray {
        require(sessionId.size == 16) { "session id must be 16 raw UUID bytes, was ${sessionId.size}" }
        val body = payload ?: ByteArray(payloadBytes.coerceAtLeast(0))
        val out = ByteArray(HEADER_SIZE + body.size)

        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = type.toByte()
        writeU16(out, 6, 0)
        sessionId.copyInto(out, 8)
        writeU32(out, 24, sequence)
        writeU64(out, 28, monotonicNanos)
        writeU64(out, 36, wallMillis)
        writeU16(out, 44, body.size)
        writeU16(out, 46, 0)
        body.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Returns null when the datagram is not a well-formed MNDP packet. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val payloadLen = readU16(bytes, 44)
        if (HEADER_SIZE + payloadLen > bytes.size) return null
        return Decoded(
            version = bytes[4].toInt() and 0xFF,
            type = bytes[5].toInt() and 0xFF,
            sessionId = bytes.copyOfRange(8, 24),
            sequence = readU32(bytes, 24),
            monotonicNanos = readU64(bytes, 28),
            wallMillis = readU64(bytes, 36),
            payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen),
        )
    }

    /** Extract the collector's `(t2, t3)` reference stamps from a TIME_RESPONSE payload. */
    fun timeStamps(decoded: Decoded): Pair<Long, Long>? {
        if (decoded.type != TYPE_TIME_RESPONSE) return null
        if (decoded.payload.size < TIME_PAYLOAD_SIZE) return null
        return readU64(decoded.payload, 0) to readU64(decoded.payload, 8)
    }

    data class Decoded(
        val version: Int,
        val type: Int,
        val sessionId: ByteArray,
        val sequence: Int,
        val monotonicNanos: Long,
        val wallMillis: Long,
        val payload: ByteArray,
    ) {
        // ByteArray fields force explicit structural equality.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return version == other.version &&
                type == other.type &&
                sequence == other.sequence &&
                monotonicNanos == other.monotonicNanos &&
                wallMillis == other.wallMillis &&
                sessionId.contentEquals(other.sessionId) &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + type
            result = 31 * result + sequence
            result = 31 * result + monotonicNanos.hashCode()
            result = 31 * result + wallMillis.hashCode()
            result = 31 * result + sessionId.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private fun writeU16(dst: ByteArray, at: Int, value: Int) {
        dst[at] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(dst: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) dst[at + i] = ((value ushr (8 * (3 - i))) and 0xFF).toByte()
    }

    private fun writeU64(dst: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) dst[at + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }

    private fun readU16(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 8) or (src[at + 1].toInt() and 0xFF)

    private fun readU32(src: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (src[at + i].toInt() and 0xFF)
        return v
    }

    private fun readU64(src: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[at + i].toLong() and 0xFF)
        return v
    }
}
