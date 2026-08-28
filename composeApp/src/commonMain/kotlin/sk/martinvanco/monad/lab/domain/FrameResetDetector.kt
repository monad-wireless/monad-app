package sk.martinvanco.monad.lab.domain

import kotlin.math.sqrt

/**
 * Detects when the tracker re-creates its origin mid-walk, so a walk that changed coordinate system
 * says so instead of looking like a walk with one bad sticker.
 *
 * THE FAILURE THIS EXISTS FOR. On 2026-08-28 a 32-minute survey walk tracked 99.83 % `normal`,
 * dwelled at all 37 targets, and uploaded every artefact. Twenty-six minutes in, ARKit re-created
 * the session origin: the pose jumped 15.12 m to exactly `(0, 0)` and the yaw moved 92° in one
 * sample. The six waypoints recorded afterwards were in a coordinate system sharing nothing with
 * the thirty-one before them. Fitted together they returned a scale ratio of 1.21 and a 16.75 m
 * residual on the one fleet node that fell after the break — which reads exactly like a mis-scanned
 * sticker, and was written up as one before the pose track was read.
 *
 * **Nothing in the session recorded it.** The console announced the relocalisation at session start
 * and the world-map save at close, and stayed silent for the event that broke the frame. The
 * platform callbacks did not fire either: there was no [PoseTrackerEvent.Interrupted], the samples
 * either side are both `normal`, and the relocalisation watchdog had stood down at the first normal
 * pose twenty-five minutes earlier. So the only witness was the arithmetic — between the two
 * waypoints either side, the participant *walked* 8.09 m and *moved* 18.04 m, and a path shorter
 * than its own displacement is impossible.
 *
 * Hence a detector rather than a callback. It is fed the same pose stream the artefact records, and
 * it is pure and clock-free so the trap is pinned by a test rather than by another walk.
 *
 * TWO TESTS, AND THE SECOND IS THE ONE THAT TOOK MEASURING
 * --------------------------------------------------------
 * **A reset displaces the origin.** A step must exceed both [PoseTrackSummary.MAX_WALK_SPEED_MPS]
 * and [MIN_DISPLACEMENT_M]. The speed test alone is not enough: the sample straight after a 15 m
 * reset also reads over 3 m/s, and reporting that too would raise a second reset out of the tail of
 * the first. Below [MIN_DISPLACEMENT_M] the two frames are interchangeable at the accuracy any
 * walk-derived position claims — the phone-to-sticker offset alone is 0.2–0.5 m — so splitting
 * there would cost anchors and buy nothing.
 *
 * **A reset is not undone.** ARKit also throws the pose out and pulls it back: on the same walk by
 * `(+7.59, −8.40)` and then `(−7.59, +8.40)` 1.9 s later, summing to a millimetre. That is one
 * frame with a hole in it, and calling it two would have cost eleven anchors. So a candidate is
 * **held** for [CONFIRM_WINDOW_NANOS] and cancelled if a later jump returns the pose to somewhere
 * the walker could plausibly have reached meanwhile.
 *
 * A persistence test was written first, on the offline copy of this logic, and it is wrong: asking
 * whether the displacement still stands a few seconds later flags the *returning* step of a glitch
 * pair, because after the return the track does sit far from where the excursion had put it.
 * Pairing asks the question the right way round.
 *
 * The cost of the hold is that the operator learns about a reset [CONFIRM_WINDOW_NANOS] after it
 * happened rather than instantly. That is the right trade — a retracted warning teaches an operator
 * to ignore warnings — and the emitted [Reset] carries the instant of the jump itself, not of the
 * confirmation, so the record is not smeared by the delay.
 */
class FrameResetDetector {

    /**
     * A confirmed origin reset.
     *
     * Every field is measured. [atMonotonicNanos] is when the frame changed, not when this was
     * confirmed, so a marker written from it lands on the discontinuity that the offline reduction
     * finds independently in `pose.tsv`.
     */
    data class Reset(
        val atMonotonicNanos: Long,
        /** How far the origin moved, metres. */
        val displacementMetres: Double,
        /** The dropout the reset arrived with, seconds — 0.767 s on the walk this was written for. */
        val gapSeconds: Double,
    )

    private data class Candidate(
        val atMonotonicNanos: Long,
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val displacementMetres: Double,
        val gapSeconds: Double,
    )

    private var previous: PoseSample? = null
    private var pending: Candidate? = null

    /**
     * Feed one pose. Returns a reset once the hold has confirmed one, else null.
     *
     * At most one [Reset] per call, and never for the pose that produced it — the candidate is only
     * released by a *later* pose that fails to cancel it.
     */
    fun onPose(sample: PoseSample): Reset? {
        val last = previous
        previous = sample
        if (last == null ||
            last.quality == TrackingQuality.UNAVAILABLE ||
            sample.quality == TrackingQuality.UNAVAILABLE
        ) {
            // A pose the platform disowned is not evidence of anything, in either direction: it
            // cannot confirm a reset and it must not cancel one.
            return null
        }

        val dx = (sample.x - last.x).toDouble()
        val dy = (sample.y - last.y).toDouble()
        val dz = (sample.z - last.z).toDouble()
        val step = sqrt(dx * dx + dy * dy + dz * dz)
        val intervalNanos = sample.monotonicNanos - last.monotonicNanos
        val jumped = !PoseTrackSummary.isLocomotion(step, intervalNanos) && step > MIN_DISPLACEMENT_M

        val held = pending
        if (held != null && jumped) {
            val gapSeconds = (sample.monotonicNanos - held.atMonotonicNanos) / 1e9
            val netX = held.dx + dx
            val netY = held.dy + dy
            val netZ = held.dz + dz
            val net = sqrt(netX * netX + netY * netY + netZ * netZ)
            if (net <= PoseTrackSummary.MAX_WALK_SPEED_MPS * gapSeconds) {
                // The excursion returned. Both steps belong to one glitch, and neither is a reset.
                pending = null
                return null
            }
        }

        val confirmed = held?.takeIf { sample.monotonicNanos - it.atMonotonicNanos >= CONFIRM_WINDOW_NANOS }
        if (confirmed != null) pending = null
        if (jumped && pending == null) {
            pending = Candidate(
                atMonotonicNanos = sample.monotonicNanos,
                dx = dx,
                dy = dy,
                dz = dz,
                displacementMetres = step,
                gapSeconds = if (intervalNanos > 0) intervalNanos / 1e9 else 0.0,
            )
        }
        return confirmed?.let {
            Reset(
                atMonotonicNanos = it.atMonotonicNanos,
                displacementMetres = it.displacementMetres,
                gapSeconds = it.gapSeconds,
            )
        }
    }

    /**
     * Release a candidate the stream ended on, at close.
     *
     * A reset in the last seconds of a walk is still a reset, and the poses after it are still in
     * their own frame. Without this the hold would swallow exactly the case where the operator has
     * stopped walking and is about to stop the session.
     */
    fun flush(): Reset? {
        val held = pending ?: return null
        pending = null
        return Reset(
            atMonotonicNanos = held.atMonotonicNanos,
            displacementMetres = held.displacementMetres,
            gapSeconds = held.gapSeconds,
        )
    }

    companion object {
        /**
         * How far the origin must move for a discontinuity to be a new frame rather than noise.
         *
         * Kept numerically identical to `monad_knowledge.walk.session.FRAME_BREAK_M`, on the same
         * reasoning as [PoseTrackSummary.MAX_WALK_SPEED_MPS]: the phone's live reading and the
         * offline reduction must split a walk in the same places, or the operator and the analyst
         * are looking at two different walks.
         */
        const val MIN_DISPLACEMENT_M: Double = 1.0

        /**
         * How long a candidate is held before it is believed, nanoseconds.
         *
         * Three seconds. The one measured glitch pair returned after 1.9 s, and the offline copy of
         * this logic uses a 30 s window because lookahead is free once the file is written. On the
         * phone it is not free — it is a warning the operator has not been given yet — so the window
         * is the smallest that clears the behaviour actually observed, with margin.
         */
        const val CONFIRM_WINDOW_NANOS: Long = 3_000_000_000L
    }
}
