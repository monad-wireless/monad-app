package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `payload_json` column of a `waypoint` row in `markers.tsv`.
 *
 * **This is a data contract**, on the same terms as [BlockMarkerPayload]: the analysis joins on
 * these names, so a rename is a silent corpus split and they are pinned here and round-tripped by a
 * test.
 *
 * A waypoint is one correspondence between two coordinate frames — the printed card's surveyed
 * position, and the pose the tracker held when the card was scanned. Both halves must be in the same
 * row, because the value of a waypoint is the *pair*. Storing only the code would leave the reader
 * to interpolate `pose.tsv` at the marker's timestamp, which is doable but throws away the one
 * moment the two frames were simultaneously observed.
 *
 * The site-frame coordinates are deliberately **not** here. A card's location is not printed on it
 * and is not in the app: it lives in the placement record, for the same reason a node's role is not
 * on its label — the set gets re-laid between sessions and a printed coordinate would go stale
 * silently. The payload carries the [code], which is what identifies the card, and the placement
 * record supplies where it was that day.
 */
@Serializable
data class WaypointMarkerPayload(
    @SerialName("schema") val schema: String = SCHEMA,
    /** The scanned string, verbatim. This is the card's identity — see `lab_marker_svg`. */
    val code: String,
    /** Operator note typed at the scan, if any. Free text, never parsed. */
    val note: String? = null,
    /**
     * The pose at the scan, in the session-local frame, or null when nothing was tracking.
     *
     * Null is a real answer and not a defect: a waypoint scanned on a handset with no odometry, or
     * before tracking initialised, still fixes a time to a place. It just cannot anchor a
     * trajectory, and a reader must be able to tell the two cases apart.
     */
    val pose: WaypointPose? = null,
    /**
     * The room the **quest asserted** this code stands in, e.g. `library-open`.
     *
     * Not a contradiction of the paragraph above. That paragraph refuses to carry a *coordinate*,
     * because a coordinate printed into an artefact goes stale the first time a card is re-laid and
     * nothing notices. This is a different fact: it is what the quest running at the time claimed,
     * and the quest is regenerated from the PostGIS placement layouts, so recording it is
     * provenance rather than a second copy of the survey. The placement record still supplies where
     * the card actually was; this says what the participant was told.
     *
     * Null for a waypoint recorded from the walk console, which has no quest to assert anything.
     */
    val room: String? = null,
    /**
     * `card` or `node` — which kind of surveyed point was scanned. Null outside a probe step.
     *
     * Load-bearing for the analysis, not a label. A dwell at a node sticker sits at zero distance
     * from one end of every link that node terminates — the degenerate corner of the geometry,
     * where grad N on the line of sight is zero. A dwell at a marker card samples open floor.
     * Pooling the two yields a statistic that cannot be interpreted, so the split has to be
     * expressible from the artefact alone.
     */
    @SerialName("target_kind") val targetKind: String? = null,
    /**
     * A **surveyed** site-frame coordinate for this code, typed by the operator at the scan.
     *
     * THE ONE EXCEPTION to the paragraph above, and it is an exception on purpose. That paragraph
     * refuses to carry the *placement record's* coordinate, because a coordinate copied into an
     * artefact goes stale the first time a card is re-laid and nothing notices. This is the opposite
     * direction: it is a measurement the operator took **in this session, with a tape**, and it is
     * the only thing in the session that ties the walk's arbitrary session frame to the building.
     *
     * Without it a walk is a shape with no place. Two anchors fix a rigid transform (one rotation,
     * one translation, scale pinned at 1), three make it a least-squares fit with a residual per
     * card, and every other waypoint then rides that transform into the site frame. Before this
     * field existed the anchors had to be written on paper and re-entered by hand afterwards, which
     * is a step that gets skipped and a number that gets transcribed wrong.
     *
     * Null on every ordinary waypoint, and that is the normal case: most cards are the *targets* of
     * the transform, not anchors for it.
     */
    val anchor: WaypointAnchor? = null,
) {
    companion object {
        /**
         * v3 adds [anchor] (2026-08-26).
         *
         * v2 added [room] and [targetKind] (IP-140).
         *
         * A version rather than a silent addition, each time, because **absence changes meaning**. A
         * v3 payload with `anchor = null` says the operator was asked and did not supply one. A v2
         * payload says nothing at all about anchoring, because the build that wrote it could not
         * express one — so a reader that treats the two alike would conclude that the 2026-08-26
         * survey walk had no anchors when in fact it had no field to put them in.
         *
         * The same rule read forwards: a v2 payload with `target_kind = null` came from a surface
         * with no quest to assert a kind (the walk console), while a v1 payload could not express a
         * kind at all.
         */
        const val SCHEMA: String = "monad-app/waypoint-marker/v3"
    }
}

/**
 * A surveyed position in the **site** frame, metres, as the operator measured it.
 *
 * (x, y) rather than (x, z): this is the floor bundle's plane, not ARKit's. The pose in the same row
 * is (x, y, z) with +y up, so the pair in one waypoint is exactly one correspondence between the two
 * frames — session `(x, z)` against site `(x, y)`.
 *
 * [source] records HOW the number was obtained, because the error term differs by an order of
 * magnitude and the analysis has to weight accordingly. A tape measure against a named origin is
 * centimetres. A node sticker whose position came out of the fleet survey is centimetres too, but it
 * is a different measurement by a different person on a different day. A coordinate read off a plan
 * is decimetres at best. Recording "surveyed" without saying by what would make all three the same
 * claim.
 */
@Serializable
data class WaypointAnchor(
    val x: Double,
    val y: Double,
    /** `tape` | `fleet-survey` | `plan`. Free-form, never parsed — read by a person. */
    val source: String = SOURCE_TAPE,
) {
    companion object {
        /** Measured in the room with a tape against the floor bundle's own origin. */
        const val SOURCE_TAPE: String = "tape"
    }
}

/** The tracker's answer at the instant of a scan. Metres and a unit quaternion, session-local. */
@Serializable
data class WaypointPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    /**
     * Tracking quality at the scan — see [TrackingQuality].
     *
     * Load-bearing for the fit. A correspondence taken while the platform said `limited` is a
     * correspondence to a position the platform disowned, and weighting it equally with a `normal`
     * one would pull the whole transform toward the worst fix of the walk.
     */
    val quality: String,
    /** Monotonic stamp of the pose used, which may lag the scan by up to one sample period. */
    @SerialName("pose_mono_ns") val poseMonotonicNanos: Long,
) {
    companion object {
        fun of(sample: PoseSample): WaypointPose = WaypointPose(
            x = sample.x,
            y = sample.y,
            z = sample.z,
            qx = sample.qx,
            qy = sample.qy,
            qz = sample.qz,
            qw = sample.qw,
            quality = sample.quality.wire,
            poseMonotonicNanos = sample.monotonicNanos,
        )
    }
}
