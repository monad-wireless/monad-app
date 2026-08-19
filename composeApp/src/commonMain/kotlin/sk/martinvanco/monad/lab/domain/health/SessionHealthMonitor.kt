package sk.martinvanco.monad.lab.domain.health

import sk.martinvanco.monad.lab.domain.ClockGateReport

/**
 * Absolute counters for every stream, read once per heartbeat.
 *
 * Counters rather than events, because the illuminator's send loop is the measurement and must not
 * grow a callback for the benefit of a status display. Every number here is one the instrument
 * already maintains.
 */
data class StreamCounters(
    val illuminator: Long = 0,
    val witness: Long = 0,
    val transitions: Long = 0,
    val groundTruth: Long = 0,
    val clock: Long = 0,
    val pose: Long = 0,
) {
    fun of(stream: LabStream): Long = when (stream) {
        LabStream.ILLUMINATOR -> illuminator
        LabStream.WITNESS -> witness
        LabStream.TRANSITIONS -> transitions
        LabStream.GROUND_TRUTH -> groundTruth
        LabStream.CLOCK -> clock
        LabStream.POSE -> pose
    }
}

/**
 * The instrument's health as one value: what every applicable stream is doing, plus the clock gate.
 *
 * [overall] is the worst current state across streams, so a single glance answers the participant's
 * question ("am I recording?") and the operator's ("is anything quietly wrong?").
 */
data class InstrumentHealth(
    val streams: List<StreamHealth> = emptyList(),
    val clockGate: ClockGateReport = ClockGateReport.NOT_RUN,
    val sessionElapsedMillis: Long = 0,
    val isRecording: Boolean = false,
) {
    val overall: StreamState
        get() = streams.maxByOrNull { it.state.severity }?.state ?: StreamState.NOT_APPLICABLE

    fun stream(kind: LabStream): StreamHealth? = streams.firstOrNull { it.stream == kind }

    /** Streams that are currently not fine — the list an operator should read first. */
    val troubled: List<StreamHealth> get() = streams.filterNot { it.state.isHealthy }

    /** Streams that were not fine at some point, even if they are now. The 42-minute question. */
    val everTroubled: List<StreamHealth> get() = streams.filter { it.hadTrouble }

    /**
     * True when the session, as it stands, would produce data the pre-registration accepts:
     * nothing dead, and enough clock samples for gate G4's affine fit.
     */
    val wouldPassReview: Boolean
        get() = streams.none { it.state == StreamState.DEAD } && !clockGate.wouldFailGate

    companion object {
        val IDLE = InstrumentHealth()
    }
}

/**
 * Drives a [StreamHealthTracker] per applicable stream from a heartbeat.
 *
 * Which streams are applicable is decided once, at session start, from the session request: a
 * witness-only participant has no illuminator and no clock, and reporting those as DEAD would be a
 * false alarm that teaches the operator to ignore the panel.
 */
class SessionHealthMonitor(
    policies: Map<LabStream, StreamPolicy>,
    private val startedAtMillis: Long,
) {
    private val trackers: List<StreamHealthTracker> =
        LabStream.entries.mapNotNull { stream ->
            policies[stream]?.let { StreamHealthTracker(stream, it, startedAtMillis) }
        }

    val applicableStreams: List<LabStream> get() = trackers.map { it.stream }

    /** Fold in one heartbeat and return the whole picture. */
    fun tick(
        counters: StreamCounters,
        nowMillis: Long,
        clockGate: ClockGateReport = ClockGateReport.NOT_RUN,
        isRecording: Boolean = true,
    ): InstrumentHealth {
        trackers.forEach { it.observe(counters.of(it.stream), nowMillis) }
        return InstrumentHealth(
            streams = trackers.map { it.snapshot(nowMillis) },
            clockGate = clockGate,
            sessionElapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0),
            isRecording = isRecording,
        )
    }

    companion object {
        /**
         * Build the applicable set for a session.
         *
         * @param emitting the session plays the illuminator role — only then is there a commanded
         *   rate to fall short of, and only then is there a socket over which the clock can be
         *   disciplined at all.
         */
        fun forSession(
            emitting: Boolean,
            commandedRateHz: Double,
            witnessing: Boolean,
            resyncSeconds: Int,
            startedAtMillis: Long,
            tracking: Boolean = false,
            poseRateHz: Double = 0.0,
            /**
             * The session disciplines its clock at all — over the collector's socket, or over the
             * reference-clock path a session with no collector uses.
             *
             * Split out of [emitting] because they stopped being the same question. A walk has no
             * collector and still produces `clock.tsv`, and reporting that stream as NOT_APPLICABLE
             * would put it in the one state that cannot fail — so a walk whose sync had quietly stopped
             * would be indistinguishable from a walk that never needed one, which is precisely the
             * silent failure this module exists for.
             */
            clockDisciplined: Boolean = emitting,
        ): SessionHealthMonitor {
            val policies = buildMap {
                if (emitting) {
                    put(LabStream.ILLUMINATOR, StreamPolicies.illuminator(commandedRateHz))
                }
                if (clockDisciplined) put(LabStream.CLOCK, StreamPolicies.clock(resyncSeconds))
                if (witnessing) {
                    put(LabStream.WITNESS, StreamPolicies.witness())
                    put(LabStream.TRANSITIONS, StreamPolicies.transitions())
                }
                // Ground truth is always applicable: a participant can scan a doorway code whether
                // or not this phone happens to be instrumenting, and that is the point of it.
                put(LabStream.GROUND_TRUTH, StreamPolicies.groundTruth())
                // Pose only when the session asked for it. A walk on a handset with no odometry must
                // read as a session that does not play the role, not as one whose track is dead —
                // NOT_APPLICABLE and DEAD are different sentences and only one of them is a fault.
                if (tracking) put(LabStream.POSE, StreamPolicies.pose(poseRateHz))
            }
            return SessionHealthMonitor(policies, startedAtMillis)
        }
    }
}
