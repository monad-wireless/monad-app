package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The phone's own position over time — the ground-truth channel a fingerprinting walk needs.
 *
 * Every other stream in this instrument records what the *radio* did. A fingerprint is a mapping
 * from a place to a signal, so it needs the other half: where the handset was when the fleet heard
 * its identity frame. Nothing else on the phone can supply that. Anchor witnessing gives a zone,
 * which is a room-sized label, and a scanned marker gives one surveyed point at one instant. A walk
 * needs the line between the points.
 *
 * Visual-inertial odometry supplies it. The track is **session-local**: the origin is wherever
 * tracking started and the axes are gravity-aligned, so the numbers are metres relative to that
 * frame and not coordinates in the site's CRS. Two things turn one into the other, and both are
 * recorded rather than assumed:
 *
 *  * a [SessionMarker.Kind.WAYPOINT] carries the pose at the moment a surveyed marker was scanned,
 *    which is a correspondence between the two frames,
 *  * three or more such correspondences over a walk determine the rigid transform *and* bound the
 *    drift that accumulated between them.
 *
 * So a walk with no waypoints still yields a shape and a length. A walk with waypoints yields a
 * trajectory in the site frame, with a measured error. That difference is the reason a waypoint is
 * an operator action and not an optional extra.
 */
data class PoseSample(
    /** Device monotonic nanoseconds — the column every other stream joins on. */
    val monotonicNanos: Long,
    val wallMillis: Long,
    /** Metres in the session-local frame. Gravity-aligned, so +y is up and the floor is y ≈ const. */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Unit quaternion of the device orientation in the same frame. */
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val quality: TrackingQuality,
    /**
     * Why tracking is degraded, when it is. Recorded per sample rather than logged, because the
     * analysis has to be able to drop the windows where the position was a guess — and a track that
     * cannot say which windows those were has to be dropped whole.
     */
    val reason: String? = null,
)

/**
 * How much the platform trusts the pose it just returned.
 *
 * This is not a nicety. Odometry does not fail loudly: it keeps returning a position while it is
 * lost, and the position keeps looking plausible. A track without this column is a track whose bad
 * segments are indistinguishable from its good ones.
 */
enum class TrackingQuality {
    /** No pose at all — the session is not tracking, or the platform refused. */
    UNAVAILABLE,

    /** A pose is being produced, but the platform says it is not to be trusted. */
    LIMITED,

    /** Tracking normally. */
    NORMAL,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): TrackingQuality =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: UNAVAILABLE
    }
}

/**
 * What the platform accepted when tracking started.
 *
 * The commanded rate is a request, exactly as it is for the illuminator: odometry runs at the
 * camera's frame rate and this samples it, so a phone that thermally throttles delivers fewer poses
 * than it was asked for. The delivered rate is measured separately, by the health monitor.
 */
@Serializable
data class PoseTrackReport(
    /** Which platform API produced the track, e.g. `arkit-world-tracking`. */
    val implementation: String,
    @SerialName("commanded_rate_hz") val commandedRateHz: Double,
    /**
     * True when a depth sensor is feeding the tracker — LiDAR scene reconstruction on iOS.
     *
     * Recorded because it changes what the track *is*, not merely how good it is: depth-assisted
     * tracking holds scale and drifts materially less over a long walk, and two walks that differ
     * on this flag are not the same measurement.
     */
    @SerialName("depth_assisted") val depthAssisted: Boolean,
    /** `gravity` — stated so a reader never has to infer which way is up. */
    @SerialName("world_alignment") val worldAlignment: String,
    val notes: List<String> = emptyList(),
)

/**
 * What a finished track amounts to, for the sidecar.
 *
 * Pure, so it is testable without a camera, and computed rather than trusted: the path length is
 * the number that tells an operator whether the walk they think they took is the walk the phone
 * recorded. A forty-metre corridor that came back as four metres is a tracker that never
 * initialised, and that is visible here and nowhere else on the phone.
 */
@Serializable
data class PoseTrackSummary(
    val samples: Long,
    /** Fraction of samples the platform called [TrackingQuality.NORMAL]. */
    @SerialName("normal_fraction") val normalFraction: Double,
    /** Sum of consecutive displacements, metres. Not the straight-line distance. */
    @SerialName("path_length_m") val pathLengthMetres: Double,
    /** Bounding-box side lengths of the horizontal extent, metres. */
    @SerialName("extent_x_m") val extentXMetres: Double,
    @SerialName("extent_z_m") val extentZMetres: Double,
    /** Peak-to-peak vertical excursion, metres. A floor change shows up here and only here. */
    @SerialName("extent_y_m") val extentYMetres: Double,
) {
    companion object {
        val EMPTY = PoseTrackSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Reduce a track.
         *
         * Displacements from [TrackingQuality.UNAVAILABLE] samples are skipped: a pose the platform
         * disowned would otherwise contribute a jump to the path length, and a jump inflates it in
         * exactly the direction that makes a broken track look like a long walk.
         */
        fun of(samples: List<PoseSample>): PoseTrackSummary {
            if (samples.isEmpty()) return EMPTY
            var length = 0.0
            var normal = 0L
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            var previous: PoseSample? = null
            samples.forEach { sample ->
                if (sample.quality == TrackingQuality.NORMAL) normal++
                if (sample.x < minX) minX = sample.x
                if (sample.x > maxX) maxX = sample.x
                if (sample.y < minY) minY = sample.y
                if (sample.y > maxY) maxY = sample.y
                if (sample.z < minZ) minZ = sample.z
                if (sample.z > maxZ) maxZ = sample.z
                val last = previous
                if (last != null &&
                    last.quality != TrackingQuality.UNAVAILABLE &&
                    sample.quality != TrackingQuality.UNAVAILABLE
                ) {
                    val dx = (sample.x - last.x).toDouble()
                    val dy = (sample.y - last.y).toDouble()
                    val dz = (sample.z - last.z).toDouble()
                    length += sqrt(dx * dx + dy * dy + dz * dz)
                }
                previous = sample
            }
            return PoseTrackSummary(
                samples = samples.size.toLong(),
                normalFraction = normal.toDouble() / samples.size,
                pathLengthMetres = length,
                extentXMetres = (maxX - minX).toDouble(),
                extentZMetres = (maxZ - minZ).toDouble(),
                extentYMetres = (maxY - minY).toDouble(),
            )
        }
    }
}

/**
 * The track as it accumulates, for the console.
 *
 * Held by the instrument rather than by the screen, so it survives the console being closed
 * mid-walk. Path length is a **running sum**, on the same reasoning as the illuminator's streaming
 * interval moments: a three-hour walk must not need its own history in memory to display one number.
 *
 * The number this exists to show is [pathLengthMetres]. An operator can look at a forty-metre
 * corridor and at a readout saying four metres, and know in one second that the tracker never
 * initialised. No other single value on the phone catches that while it is still free to fix.
 */
data class PoseTrackProgress(
    val samples: Long = 0,
    val pathLengthMetres: Double = 0.0,
    /** The most recent pose, or null before the first one arrives. */
    val last: PoseSample? = null,
    /** Samples the platform called [TrackingQuality.NORMAL]. */
    val normalSamples: Long = 0,
) {
    val quality: TrackingQuality get() = last?.quality ?: TrackingQuality.UNAVAILABLE

    /** Null before the first sample — an unknown fraction, which is not the same as zero. */
    val normalFraction: Double? get() = if (samples > 0) normalSamples.toDouble() / samples else null

    /**
     * Fold one pose in.
     *
     * Skips the displacement across an [TrackingQuality.UNAVAILABLE] sample for the same reason
     * [PoseTrackSummary.of] does: a disowned pose contributes a jump, and a jump inflates the length
     * in exactly the direction that makes a broken track look like a long walk.
     */
    fun plus(sample: PoseSample): PoseTrackProgress {
        val previous = last
        val step = if (
            previous != null &&
            previous.quality != TrackingQuality.UNAVAILABLE &&
            sample.quality != TrackingQuality.UNAVAILABLE
        ) {
            val dx = (sample.x - previous.x).toDouble()
            val dy = (sample.y - previous.y).toDouble()
            val dz = (sample.z - previous.z).toDouble()
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        } else {
            0.0
        }
        return PoseTrackProgress(
            samples = samples + 1,
            pathLengthMetres = pathLengthMetres + step,
            last = sample,
            normalSamples = normalSamples + if (sample.quality == TrackingQuality.NORMAL) 1 else 0,
        )
    }

    companion object {
        val IDLE = PoseTrackProgress()
    }
}

/**
 * Visual-inertial odometry, when the platform has it.
 *
 * Deliberately shaped like [IdentityBroadcaster] rather than like [LabSensorModule]: this is not a
 * one-shot capture that returns an artefact, it is a stream that runs for the length of a session
 * and is judged by its liveness. A sensor module could not be watched by the health monitor, and an
 * odometry track that silently stops is the failure that matters most.
 *
 * Both roles this composes with are foreground-only on iOS — advertising moves into an overflow
 * area the fleet cannot read, and ARKit pauses outright. That is not a limitation to work around,
 * it is the shape of the activity: a fingerprinting walk is somebody holding a phone and walking.
 */
expect class PoseTracker() {

    /** Poses as they arrive. Cold until [start]. */
    val samples: Flow<PoseSample>

    /**
     * Runtime truth, separate from "the OS has the framework".
     *
     * A device can have ARKit and still refuse: camera permission revoked, or hardware that world
     * tracking does not support. An [LabSensorModule.Availability.Unsupported] carries the reason,
     * because "the walk recorded no positions" is only debuggable if the phone said why at the time.
     */
    suspend fun probe(): LabSensorModule.Availability

    /** Begin tracking at a commanded sampling rate. Returns what the platform accepted. */
    suspend fun start(rateHz: Double): Result<PoseTrackReport>

    /**
     * Cheap pass over the mesh blocks: which ones exist, where they are, and whether any changed.
     *
     * Returns only blocks that are **new or changed** since the last call, because that is the whole
     * content of the record. A periodic dump of every block would write thousands of identical rows,
     * and the rows that matter — a block whose geometry moved after the walk had already passed it —
     * would be indistinguishable from the noise.
     *
     * Does not copy geometry. Empty when the session did not enable scene reconstruction, which is a
     * different answer from a session that enabled it and has discovered nothing yet — the log's first
     * row is what distinguishes them.
     */
    suspend fun observeMesh(): List<MeshObservation>

    /**
     * Export every mesh block as one file, in the **session-local frame** the poses are in.
     *
     * Called once, at close. Failure carries a reason rather than an empty mesh: a PLY with a header
     * and no triangles is a successful-looking export of nothing, which is the outcome this whole
     * subsystem is built to avoid.
     */
    suspend fun snapshotMesh(): Result<MeshSnapshot>

    fun stop()

    /** Platform posture, shown in the console so a refusal is visible before a walk starts. */
    fun diagnostics(): List<String>
}

/**
 * Matrix-to-quaternion conversion, kept here rather than in the platform source.
 *
 * ARKit hands back a column-major 4×4 rigid transform. Getting a quaternion out of its rotation
 * basis is four lines of arithmetic with one trap — the naive `w`-first formula divides by zero for
 * a 180° rotation, which is a phone held pointing backwards along the walk. Shanks's branch on the
 * largest diagonal term avoids it. Pure, so the trap is pinned by a test and not by a walk.
 */
object PoseGeometry {

    /**
     * Convert the rotation part of a column-major transform to a unit quaternion.
     *
     * @param c0 first basis column (x axis of the device frame, in world coordinates)
     * @param c1 second basis column
     * @param c2 third basis column
     */
    fun quaternion(
        c0: FloatArray,
        c1: FloatArray,
        c2: FloatArray,
    ): FloatArray {
        val m00 = c0[0]
        val m10 = c0[1]
        val m20 = c0[2]
        val m01 = c1[0]
        val m11 = c1[1]
        val m21 = c1[2]
        val m02 = c2[0]
        val m12 = c2[1]
        val m22 = c2[2]

        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s
            q[0] = 0.25f * s
            q[1] = (m01 + m10) / s
            q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s
            q[1] = 0.25f * s
            q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s
            q[1] = (m12 + m21) / s
            q[2] = 0.25f * s
        }
        val norm = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (norm > 0f && abs(norm - 1f) > 1e-4f) {
            for (i in 0..3) q[i] = q[i] / norm
        }
        return q
    }
}
