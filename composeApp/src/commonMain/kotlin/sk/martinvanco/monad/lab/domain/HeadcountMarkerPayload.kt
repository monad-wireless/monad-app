package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `payload_json` of a `headcount` row in `markers.tsv` (IP-140).
 *
 * **This is a data contract**, on the same terms as [WaypointMarkerPayload]: the
 * analysis joins on these names, so a rename is a silent corpus split.
 *
 * A headcount is the only number in this lab that came from a person rather than
 * from a radio. Everything else counts handsets, and everything else therefore
 * shares one blind spot — anybody who is not carrying the app. That is why this
 * must never be reconciled against the BLE count: the gap between the two *is* the
 * penetration bias, and a later experiment sets out to measure it.
 *
 * What it deliberately does not carry:
 *
 * - **A position.** The participant is walking; they stop where they stop. Asking
 *   them to also record where they were would be asking for a measurement they
 *   cannot make, and inventing one from the odometry would be worse — on the two
 *   real walks of 2026-08-19 that odometry reported 92.3 m and 161.1 m of path for
 *   a handset that barely moved. The fleet's own BLE record says where the phone
 *   was at this timestamp, far better than the phone can.
 * - **A ground truth claim.** [count] is what one person believed they could see.
 *   It is a human reading with human error, not an oracle, and the field name says
 *   `count` rather than `occupancy` for exactly that reason.
 *
 * What it DOES carry, as of v2, is [onAir] — whether the identity frame was actually
 * being broadcast when the participant pressed Record. The fleet's BLE record is what
 * places this reading, so a reading taken while the phone was silent has no position
 * and never will. Without the flag that case is indistinguishable from a fleet that
 * heard nothing, and the two call for opposite responses: one discards the reading,
 * the other investigates the receivers.
 */
@Serializable
data class HeadcountMarkerPayload(
    @SerialName("schema") val schema: String = SCHEMA,
    /** How many people the participant said they could see. */
    val count: Int,
    /** Which reading this is within its step, from 1. */
    val reading: Int,
    /** How many the step asked for, so a reader can tell a full set from a partial one. */
    @SerialName("of_readings") val ofReadings: Int,
    /** The question as it was put to them, verbatim — two prompts are two measurements. */
    val prompt: String,
    /**
     * Was the identity frame on air at this instant?
     *
     * `null` means the question was not recorded, which is every v1 row. It is NOT `false`:
     * a row written before this field existed says nothing about the radio, and reading its
     * absence as silence would discard readings that were fine.
     */
    @SerialName("on_air") val onAir: Boolean? = null,
) {
    companion object {
        /** v2 (2026-09-18) added [onAir]. A v1 row carries no on-air fact, not a negative one. */
        const val SCHEMA: String = "monad-app/headcount-marker/v2"
    }
}
