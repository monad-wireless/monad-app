package sk.martinvanco.monad.lab.domain

import sk.martinvanco.monad.lab.domain.health.StreamState

/**
 * The session-complete summary — the thing an operator screenshots and pastes into the log.
 *
 * Derived entirely from the sidecar, so it is available for any past session on the device and not
 * only for the one that just ended, and so it is a pure function that can be tested against a
 * handful of representative sidecars.
 *
 * The ordering of [verdicts] is the ordering of the start-up gates the instrument itself follows —
 * residency, binding, clock, streams — so the first red line is the one that matters.
 */
data class SessionReport(
    val sessionId: String,
    val participantId: String,
    val questId: String,
    val site: String,
    val roles: List<String>,
    val startedWallMillis: Long,
    val endedWallMillis: Long,
    val durationMillis: Long,
    val interruptedReason: String?,
    val monotonicContinuous: Boolean,
    val verdicts: List<Verdict>,
    val counts: List<Pair<String, Long>>,
) {
    val shortId: String get() = sessionId.take(8)

    val isUsable: Boolean get() = verdicts.none { it.level == Level.BAD }

    val worstLevel: Level get() = verdicts.maxByOrNull { it.level.ordinal }?.level ?: Level.GOOD

    /** Headline for the top of the summary card. */
    val headline: String
        get() = when {
            interruptedReason != null -> "INTERRUPTED — data kept, tail is missing"
            worstLevel == Level.BAD -> "PROBLEM — this session would not pass review"
            worstLevel == Level.WARN -> "RECORDED with warnings"
            else -> "RECORDED — everything nominal"
        }

    enum class Level { GOOD, WARN, BAD }

    /** One line of the summary: what was checked, what it said, and whether that is acceptable. */
    data class Verdict(val label: String, val detail: String, val level: Level)

    companion object {

        fun from(sidecar: LabSessionSidecar): SessionReport {
            val lifecycle = sidecar.lifecycle
            val summary = sidecar.summary
            val verdicts = mutableListOf<Verdict>()

            verdicts += if (sidecar.radio.socketPinned) {
                Verdict("Socket binding", sidecar.radio.boundInterface.ifBlank { "pinned" }, Level.GOOD)
            } else if (sidecar.identity.roles.contains("illuminator")) {
                // The design's worst failure mode: the UI says connected, the datagrams leave over
                // cellular, and the observer sees nothing at all.
                Verdict(
                    "Socket binding",
                    "NOT PINNED (${sidecar.radio.boundInterface.ifBlank { "unknown" }}) — the " +
                        "emitted stream may never have reached the experiment network",
                    Level.BAD,
                )
            } else {
                Verdict("Socket binding", "not applicable (witness-only)", Level.GOOD)
            }

            val missingResidency = sidecar.environment.residencyChecks.filter { it.contains("MISSING") }
            verdicts += if (missingResidency.isEmpty()) {
                Verdict("Background residency", "all checks satisfied", Level.GOOD)
            } else {
                Verdict("Background residency", missingResidency.joinToString("; "), Level.BAD)
            }

            val gate = sidecar.clockGate
            verdicts += when {
                gate == null -> Verdict("Clock gate G4", "not evaluated by this build", Level.WARN)
                gate.status == ClockGateStatus.NOT_APPLICABLE.wire ->
                    Verdict("Clock gate G4", "not applicable (witness-only)", Level.GOOD)

                gate.wouldFailGate -> Verdict(
                    "Clock gate G4",
                    "${gate.samples} sync sample(s) — the analysis needs " +
                        "${ClockGate.MIN_SAMPLES_FOR_FIT}; this fold would be excluded or flagged",
                    Level.BAD,
                )

                else -> Verdict(
                    "Clock gate G4",
                    "${gate.samples} sync samples, skew ${gate.skewPpm.roundTo(1)} ppm" +
                        (gate.maxFitResidualMillis?.let { ", fit residual ${it.roundTo(2)} ms" } ?: ""),
                    Level.GOOD,
                )
            }

            sidecar.health.forEach { record ->
                val worst = record.worst
                val level = when (worst) {
                    StreamState.DEAD.wire -> Level.BAD
                    StreamState.STALE.wire, StreamState.DEGRADED.wire -> Level.WARN
                    else -> Level.GOOD
                }
                val trouble = record.degradedMillis + record.staleMillis + record.deadMillis
                val detail = buildString {
                    append("${record.events} events")
                    record.deliveredFraction?.let { append(", ${(it * 100).roundTo(1)}% of commanded") }
                    if (trouble > 0) {
                        append(" — worst state $worst for ${formatDuration(trouble)}")
                    }
                }
                verdicts += Verdict(record.stream, detail, level)
            }

            if (summary.markers == 0L) {
                verdicts += Verdict(
                    "Markers",
                    "none written — the stream has no machine-readable take structure",
                    Level.WARN,
                )
            }

            verdicts += blockLabelVerdict(sidecar)

            return SessionReport(
                sessionId = sidecar.identity.sessionId,
                participantId = sidecar.identity.participantId,
                questId = sidecar.identity.questId,
                site = sidecar.identity.site,
                roles = sidecar.identity.roles,
                startedWallMillis = lifecycle.startedWallMillis,
                endedWallMillis = lifecycle.endedWallMillis,
                durationMillis = (lifecycle.endedWallMillis - lifecycle.startedWallMillis).coerceAtLeast(0),
                interruptedReason = lifecycle.interruptedReason,
                monotonicContinuous = lifecycle.monotonicContinuous,
                verdicts = verdicts,
                counts = listOf(
                    "packets sent" to summary.packetsSent,
                    "packets failed" to summary.packetsFailed,
                    "beacon rows" to summary.beaconObservations,
                    "zone transitions" to summary.zoneTransitions,
                    "markers" to summary.markers,
                ),
            )
        }

        /**
         * Did the operator label the experimental conditions?
         *
         * The one failure this whole subsystem cannot repair afterwards. Block labels *are* the
         * ground truth the analysis uses (`ExperimentBlock`), so a session with none is not a
         * session with weaker labels — it is a recording nobody can assign to a fold, and the
         * boundaries cannot be reconstructed from the count trace at exactly the cycling ramps T3
         * depends on. Nothing else in this report said anything about it, so an operator who forgot
         * to press the button got a green summary card.
         *
         * **Scoped to console sessions.** Block edges are written from the lab console only; a
         * participant's quest run takes its structure from step markers, and `identity.quest_id` is
         * blank exactly on the console path (`LabConsoleScreenModel` builds a `SessionRequest` with
         * no quest, `QuestSessionCoordinator` always sets one). Telling a participant their run
         * failed for lacking blocks would train the whole fleet to ignore this card.
         *
         * Returns an empty list rather than a "not applicable" line: a quest session's summary has
         * no business discussing a mechanism its operator never had.
         */
        private fun blockLabelVerdict(sidecar: LabSessionSidecar): List<Verdict> {
            if (sidecar.identity.questId.isNotBlank()) return emptyList()
            val summary = sidecar.summary
            val label = "Block labels"
            return listOf(
                when {
                    summary.blocks == 0L -> Verdict(
                        label,
                        "NONE — no block was ever marked, so nothing in this recording says which " +
                            "part was which condition. The label is the ground truth the analysis " +
                            "uses, so this cannot be repaired after the fact.",
                        Level.BAD,
                    )

                    // Edges come in pairs. An odd count is a block that was opened and never
                    // closed at all — the process died first — so that block's trailing edge does
                    // not exist and everything after its start is unlabelled. Bounded damage: the
                    // blocks before it are intact, which is why this is not fatal to the session.
                    summary.blocks % 2L != 0L -> Verdict(
                        label,
                        "${summary.blocks / 2} complete block(s), and one opened that was never " +
                            "closed — everything after its start edge is unlabelled",
                        Level.WARN,
                    )

                    // Recoverable by construction: the edge exists and carries
                    // stop_reason=session_end, so a reader can see why it is where it is.
                    summary.blockOpenAtSessionEnd -> Verdict(
                        label,
                        "${summary.blocks / 2} block(s); the last was closed by the session ending, " +
                            "not by the operator — its trailing edge is an artefact of the stop, so " +
                            "check its duration before using it",
                        Level.WARN,
                    )

                    else -> Verdict(label, "${summary.blocks / 2} block(s) marked and closed", Level.GOOD)
                }
            )
        }

        fun formatDuration(millis: Long): String {
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}

/**
 * Fixed-decimal rendering without `String.format`, which Kotlin/Native does not provide.
 *
 * Rounds half away from zero, which is what a reader expects of a displayed number.
 */
fun Double.roundTo(decimals: Int): String {
    if (isNaN()) return "n/a"
    if (isInfinite()) return if (this > 0) "∞" else "-∞"
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val negative = this < 0
    val scaled = kotlin.math.round(kotlin.math.abs(this) * scale).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale
    val sign = if (negative && (whole != 0L || fraction != 0L)) "-" else ""
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
}
