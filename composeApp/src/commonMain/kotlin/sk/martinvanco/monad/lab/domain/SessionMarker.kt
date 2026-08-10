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
        ;

        val wire: String
            get() = when (this) {
                STEP_BEGIN -> "step_begin"
                STEP_END -> "step_end"
                ANNOTATION -> "annotation"
                CLOCK_SYNC -> "clock_sync"
                BLOCK_START -> "block_start"
                BLOCK_STOP -> "block_stop"
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
                else -> ANNOTATION
            }
        }
    }
}
