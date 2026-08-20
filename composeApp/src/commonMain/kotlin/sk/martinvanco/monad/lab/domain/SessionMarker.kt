package sk.martinvanco.monad.lab.domain

/**
 * A labelled point on the session timeline.
 *
 * The instrument records **one continuous session**; a marker is what says which part of that
 * stream was which experimental condition — "from here, occupancy 3, arrangement o3-a, walking".
 *
 * This is deliberately not one recording per take. Restarting the radio between takes would cost a
 * re-association and a clock re-discipline each time, and would leave unrecorded gaps exactly where
 * people are moving into position. Keeping one stream and labelling it also degrades well: if the
 * UI dies mid-session the samples keep flowing and only the markers stop, so the data is truncated
 * in metadata rather than lost.
 *
 * [payload] carries the step's own config verbatim — `occupancy_count`, `arrangement_id`,
 * `posture` — so the analysis side can slice a session into takes without asking the backend what
 * the quest said.
 */
data class SessionMarker(
    val kind: Kind,
    val label: String,
    /** Backend step id, when a quest step raised this marker. */
    val stepId: String? = null,
    /** Raw step config JSON, or any operator annotation payload. */
    val payload: String? = null,
    val monotonicNanos: Long,
    val wallMillis: Long,
) {
    enum class Kind {
        /** A step became active — the take opens here. */
        STEP_BEGIN,

        /** A step completed — the take closes here. */
        STEP_END,

        /** Session-level note (start of run, operator annotation, condition change). */
        ANNOTATION,

        /**
         * A clock burst completed — an anchor the analysis can measure alignment *at*.
         *
         * Gate G4's residual is evaluated at sync markers, and the pre-registration asks for at
         * least four per fold. Writing one per successful burst makes the marker budget fall out of
         * the resync cadence instead of resting on an operator remembering to fire them. The
         * payload carries the burst's own offset / rtt / skew, so a marker is self-describing even
         * if `clock.tsv` and `markers.tsv` are read separately.
         */
        CLOCK_SYNC,

        /**
         * An experimental block opened — the operator declaring which part of the frozen design is
         * running from here.
         *
         * Extends this stream rather than opening a parallel one, deliberately. A block boundary is
         * the same kind of fact as a step boundary — "from here, this window is level 4, walking,
         * cycling plateau" — and it must sit on the same `mono_ns` / `wall_ms` pair as every other
         * app stream, because that pair is what the pre-registration's §3.5 transform maps onto the
         * fleet timeline. A second stream would need its own clock story, and would get it wrong.
         *
         * `step_id` carries the `block_id` so a reader can group the two edges without parsing the
         * payload; the payload itself is a [BlockMarkerPayload].
         */
        BLOCK_START,

        /** An experimental block closed. Payload adds duration, stop reason and budget verdict. */
        BLOCK_STOP,

        /**
         * The identity broadcast went on air. The payload is the [BroadcastReport] — the accepted
         * values, not the commanded ones — so the fleet-side join reads what was actually
         * transmitted. Windows outside a start/stop pair must not be blamed on a silent radio.
         */
        BROADCAST_START,

        /** The identity broadcast left the air (step end, session end, or platform refusal). */
        BROADCAST_STOP,

        /**
         * A surveyed marker was scanned during a walk — the correspondence that turns a
         * session-local pose track into a trajectory in the site's frame.
         *
         * This is a *different fact* from a ground-truth check-in, which is why it is a marker and
         * not a `GroundTruthEvent`. A check-in says "a person crossed into this zone" and feeds the
         * people count. A waypoint says "the phone was at this printed point at this instant" and
         * feeds geometry. Recording one as the other would put a position fix into the occupancy
         * tally, and the tally is the quantity the whole calibration rests on.
         *
         * `step_id` carries the scanned payload verbatim, because that string *is* the identity of
         * the physical card. The payload is a [WaypointMarkerPayload]: the code plus the pose the
         * tracker held at the moment of the scan, so the correspondence is self-contained even if
         * `pose.tsv` and `markers.tsv` are read apart.
         */
        WAYPOINT,

        /**
         * The pose stream stopped advancing — ARKit held the same frame past several sample
         * periods, so the trajectory has a hole starting here.
         *
         * Measured 2026-08-19: walk B carried a twenty-second frame gap at t+34 s after which the
         * tracker reported `initializing` again — a mid-walk reset that nothing recorded. The rate
         * check cannot catch it (the poller skips stale frames, so the gap looks like a slow
         * stream), and the row that follows the gap says nothing about the gap itself. This marker
         * records *when* the tracker stopped producing, which is what lets the analysis cut the
         * hole out instead of interpolating across a reset.
         */
        POSE_STALLED,

        /** The pose stream advanced again. Payload carries the measured gap. */
        POSE_RESUMED,

        /**
         * The operator started standing still on a surveyed card — the stationary probe arm.
         *
         * A dwell is a different measurement from a walk: the body parked at a known distance from
         * a link's line of sight, held, so the CSI statistic can be read against a *fixed* position
         * with no direction-of-motion confound. It is the arm that can resolve the walked-vs-
         * simulated sign disagreement (ρ = −0.22 measured, +0.63 simulated, 2026-08-19), because it
         * matches the simulation's sampling geometry. `step_id` carries the card code.
         */
        DWELL_START,

        /** The operator finished the dwell. Payload adds the measured duration. */
        DWELL_END,
        ;

        val wire: String
            get() = when (this) {
                STEP_BEGIN -> "step_begin"
                STEP_END -> "step_end"
                ANNOTATION -> "annotation"
                CLOCK_SYNC -> "clock_sync"
                BLOCK_START -> "block_start"
                BLOCK_STOP -> "block_stop"
                BROADCAST_START -> "broadcast_start"
                BROADCAST_STOP -> "broadcast_stop"
                WAYPOINT -> "waypoint"
                POSE_STALLED -> "pose_stalled"
                POSE_RESUMED -> "pose_resumed"
                DWELL_START -> "dwell_start"
                DWELL_END -> "dwell_end"
            }

        /** True for the two kinds that carry a [BlockMarkerPayload]. */
        val isBlockEdge: Boolean get() = this == BLOCK_START || this == BLOCK_STOP

        companion object {
            fun fromWire(value: String): Kind = when (value) {
                "step_begin" -> STEP_BEGIN
                "step_end" -> STEP_END
                "clock_sync" -> CLOCK_SYNC
                "block_start" -> BLOCK_START
                "block_stop" -> BLOCK_STOP
                "broadcast_start" -> BROADCAST_START
                "broadcast_stop" -> BROADCAST_STOP
                "waypoint" -> WAYPOINT
                "pose_stalled" -> POSE_STALLED
                "pose_resumed" -> POSE_RESUMED
                "dwell_start" -> DWELL_START
                "dwell_end" -> DWELL_END
                else -> ANNOTATION
            }
        }
    }
}
