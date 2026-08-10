package sk.martinvanco.monad.lab.domain

/**
 * The experimental block — the operator's label for "which part of the frozen design is running
 * right now".
 *
 * Without it the analysis has to *infer* block boundaries from the count trace, which is fragile
 * exactly where it matters most: at a cycling ramp the count is moving, so a boundary inferred from
 * the count is systematically biased toward the middle of the ramp. The pre-registration
 * (`lab-session-2026-08-prereg-v2`) makes the cycling events the **primary** event set for T3, so a
 * mislabelled ramp is not a cosmetic error — it corrupts the one contrast T3 exists to measure.
 *
 * Everything in this file is pure: no clock, no I/O, no coroutines. Time is always passed in. The
 * state machine and every guard-rail are therefore checkable without a lab, a phone, or a session.
 *
 * ### The design this serves (frozen — do not "improve" these numbers)
 *
 * - Three zones inside one hall: ZONE-A / ZONE-B / ZONE-C (§2 of the pre-registration).
 * - Staircase levels {0, 1, 2, 3, 4, 6, 8, 10}; the Day-2 reduced staircase is {0, 2, 4, 8}.
 * - Two sub-conditions per level: **seated/still** and **walking/mixed**.
 * - Cycling blocks 0 → 4 → 0 → 4 → 0 → 4 → 0, plateau dwell 1.5 min, ramp 0.5 min.
 * - Sub-block budgets: 175 s (full staircase) and 350 s (reduced staircase).
 */

/** The three staged link geometries. Wire values are the pre-registration's own zone names. */
object LabZones {
    const val A: String = "ZONE-A"
    const val B: String = "ZONE-B"
    const val C: String = "ZONE-C"

    /**
     * Fallback vocabulary for a bench with no backend bundle.
     *
     * In the hall the zone list comes from the config bundle's beacon zones, so a block's `zone_id`
     * and a ground-truth scan's `zone_id` are drawn from the **same** vocabulary and the analysis
     * can join them without a lookup table. This triple exists only so the console is usable before
     * that bundle arrives.
     */
    val DEFAULT: List<String> = listOf(A, B, C)
}

/** The frozen staircase. */
object LabStaircase {
    /** Day 1 — the full staircase. */
    val LEVELS: List<Int> = listOf(0, 1, 2, 3, 4, 6, 8, 10)

    /** Day 2 — the reduced staircase that builds the second session for the LOSO axis. */
    val REDUCED_LEVELS: List<Int> = listOf(0, 2, 4, 8)

    /** The two levels a cycling block alternates between. */
    val CYCLING_LEVELS: List<Int> = listOf(0, 4)

    fun isRegistered(level: Int): Boolean = level in LEVELS
}

/**
 * The two sub-conditions run at every staircase level.
 *
 * `seated` is the never-measured condition the Doppler / RMT legs exist for; `walking` feeds the
 * amplitude-CV feature and the level-vs-change question.
 */
enum class SubCondition(val wire: String, val label: String) {
    SEATED("seated", "Seated / still"),
    WALKING("walking", "Walking / mixed"),
    ;

    companion object {
        fun fromWire(value: String?): SubCondition? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What kind of block this is, and how long the frozen design says it should last.
 *
 * The budgets are not advice. A cycling plateau that ran for four minutes is not a long plateau, it
 * is a plateau that swallowed a ramp — and the analysis, which trusts the label, would then average
 * a transition into a steady state. Surfacing the overrun while the operator is still in the room is
 * the only moment it can be fixed.
 *
 * @param designedSeconds the nominal dwell from the pre-registration, or null for an untimed block.
 * @param minSeconds below this the block is too short to be the thing it claims to be.
 * @param maxSeconds above this it has almost certainly absorbed the next one.
 * @param registeredLevels the occupancy levels this kind may legitimately carry, or null for "any
 *   registered staircase level".
 */
enum class BlockKind(
    val wire: String,
    val label: String,
    val designedSeconds: Int?,
    val minSeconds: Int?,
    val maxSeconds: Int?,
    val registeredLevels: List<Int>?,
) {
    /**
     * One `(session × zone × band × level × motion)` staircase cell.
     *
     * The band spans both registered sub-block budgets — 175 s for a full-staircase sub-block and
     * 350 s for a reduced-staircase one (§2) — with ±20 % either side, because the console does not
     * know which day it is and guessing would produce a warning the operator learns to ignore.
     */
    STAIRCASE("staircase", "Staircase", 175, 140, 420, null),

    /** A cycling dwell: 1.5 min at level 0 or 4. */
    CYCLING_PLATEAU("cycling_plateau", "Cycling plateau", 90, 72, 108, LabStaircase.CYCLING_LEVELS),

    /**
     * A cycling transition: 0.5 min moving four participants.
     *
     * `level` on a ramp is the level the room is moving **to**, so an up-ramp carries 4 and a
     * down-ramp carries 0. Stated here because it is the one field on this whole surface whose
     * meaning is not self-evident, and a ramp labelled with the wrong endpoint inverts T3's matched
     * up/down pairing.
     */
    CYCLING_RAMP("cycling_ramp", "Cycling ramp", 30, 24, 36, LabStaircase.CYCLING_LEVELS),

    /** Day-0 empty-room capture: 30 min per zone per band. Estimator input, not filler. */
    EMPTY_ROOM("empty_room", "Empty room", 1_800, 900, 3_600, listOf(0)),

    /** Placement, survey, rehearsal — anything that is not a measurement. Untimed by design. */
    SETUP("setup", "Setup", null, null, null, null),
    ;

    val isTimed: Boolean get() = minSeconds != null && maxSeconds != null

    fun isWithinBudget(durationMillis: Long): Boolean {
        val min = minSeconds ?: return true
        val max = maxSeconds ?: return true
        return durationMillis >= min * 1000L && durationMillis <= max * 1000L
    }

    companion object {
        fun fromWire(value: String?): BlockKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Which edge of a block a marker sits on. */
enum class BlockPhase(val wire: String) {
    START("start"),
    STOP("stop"),
}

/**
 * Why a block ended.
 *
 * [SESSION_END] and [SUPERSEDED] are the two the analysis must be able to tell from a deliberate
 * stop: both mean the operator never explicitly closed the block, so its trailing edge is an
 * artefact of something else happening rather than a judgement that the condition was over.
 */
enum class BlockStopReason(val wire: String, val label: String) {
    /** The operator pressed stop. */
    OPERATOR("operator", "stopped by the operator"),

    /** A new block was started while this one was still running. */
    SUPERSEDED("superseded", "auto-stopped: a new block was started"),

    /** The session ended with this block still open. */
    SESSION_END("session_end", "auto-stopped: the session ended"),
}

/** Where the live people-count on screen came from. Never inferred — always carried. */
enum class TallySource(val wire: String) {
    /** Room-wide, from the backend, fresh. */
    ROOM_LIVE("room_live"),

    /** Room-wide, from the backend, but older than the staleness bound. */
    ROOM_STALE("room_stale"),

    /** Backend never answered for this session — this handset's own scans only. */
    DEVICE_ONLY("device_only"),
}

/**
 * The live ground-truth count at the instant a block edge was marked, with its provenance.
 *
 * Provenance travels with the number because the two differ by a factor of ten in this session: a
 * device-only count of 1 against a declared level of 4 is not a discrepancy, it is a phone that has
 * never heard from the backend. A guard-rail that could not tell those apart would cry wolf on
 * every block and be switched off by lunchtime.
 */
data class BlockTally(
    val count: Int,
    val source: TallySource,
) {
    /** True when this number is a claim about the room rather than about one handset. */
    val isRoomWide: Boolean get() = source != TallySource.DEVICE_ONLY
}

/**
 * What the operator asked for. The id is supplied by the caller so this whole file stays pure and
 * the state machine can be driven deterministically from a test.
 */
data class BlockStartRequest(
    /** Stable, globally unique, generated once at the tap. Becomes `block_id` and `step_id`. */
    val blockId: String,
    val zoneId: String,
    val level: Int,
    val subCondition: SubCondition,
    val kind: BlockKind,
)

/** A block that is currently running. */
data class ActiveBlock(
    val blockId: String,
    val zoneId: String,
    val level: Int,
    val subCondition: SubCondition,
    val kind: BlockKind,
    val sequence: Int,
    val startedMonotonicNanos: Long,
    val startedWallMillis: Long,
    /** Warnings raised at the start edge, kept so the console can keep showing them. */
    val startWarnings: List<BlockWarning> = emptyList(),
) {
    fun elapsedMillis(nowMonotonicNanos: Long): Long =
        ((nowMonotonicNanos - startedMonotonicNanos) / 1_000_000L).coerceAtLeast(0)

    /** One line that is unmistakable at arm's length on a bench. */
    val headline: String
        get() = "$zoneId · L$level · ${subCondition.label} · ${kind.label}"
}

/** A block that has ended. */
data class CompletedBlock(
    val blockId: String,
    val zoneId: String,
    val level: Int,
    val subCondition: SubCondition,
    val kind: BlockKind,
    val sequence: Int,
    val startedMonotonicNanos: Long,
    val startedWallMillis: Long,
    val endedMonotonicNanos: Long,
    val endedWallMillis: Long,
    val durationMillis: Long,
    val stopReason: BlockStopReason,
    val warnings: List<BlockWarning>,
) {
    val withinBudget: Boolean get() = kind.isWithinBudget(durationMillis)

    val headline: String
        get() = "$zoneId · L$level · ${subCondition.label} · ${kind.label}"
}

/** Why a command was refused outright. A refusal writes nothing — there is nothing worth writing. */
enum class BlockRejection(val message: String) {
    /**
     * The one refusal that is not a nuisance.
     *
     * A block marker names an interval of a stream. With no session there is no stream, so the
     * marker would name nothing, and a `markers.tsv` full of boundaries with no samples between them
     * is worse than none: it looks like data.
     */
    NO_SESSION("no session is running — start the session first, or the block labels an empty stream"),

    /** Stop pressed with nothing running. */
    NO_ACTIVE_BLOCK("no block is running"),

    /** A start request that does not name a zone. */
    NO_ZONE("pick a zone first — a block with no zone cannot be assigned to a fold"),
}

/** Severity of a guard-rail finding. Neither ever blocks the operator; both are always recorded. */
enum class BlockWarningSeverity { NOTE, WARN }

/**
 * The guard-rails, as an enumerated vocabulary rather than free text.
 *
 * Enumerated because these travel into `markers.tsv` and are therefore part of the data contract:
 * an analysis that wants to exclude every block whose level disagreed with the live tally must be
 * able to select on a token, not grep a sentence.
 */
enum class BlockWarningKind(val wire: String, val severity: BlockWarningSeverity) {
    /**
     * The declared level disagrees with the live room tally.
     *
     * The cheapest possible protection against the one error that is unrecoverable after the fact:
     * a block labelled level 4 that actually ran at level 6 cannot be detected in analysis, because
     * the label is the ground truth the analysis is using.
     */
    LEVEL_DISAGREES_WITH_TALLY("level_disagrees_with_tally", BlockWarningSeverity.WARN),

    /** The tally on screen is this handset's own count, so the comparison above could not be made. */
    TALLY_NOT_ROOM_WIDE("tally_not_room_wide", BlockWarningSeverity.NOTE),

    /** The room tally is older than the staleness bound, so the comparison is weak evidence. */
    TALLY_STALE("tally_stale", BlockWarningSeverity.NOTE),

    /** The level is not one the pre-registration froze. */
    LEVEL_OFF_STAIRCASE("level_off_staircase", BlockWarningSeverity.WARN),

    /** A cycling block at a level outside the registered 0 ↔ 4 pattern. */
    LEVEL_OFF_PATTERN("level_off_pattern", BlockWarningSeverity.WARN),

    /** Ended well short of its designed dwell. */
    DURATION_UNDER_BUDGET("duration_under_budget", BlockWarningSeverity.WARN),

    /** Ran well past its designed dwell — it has probably absorbed the next block. */
    DURATION_OVER_BUDGET("duration_over_budget", BlockWarningSeverity.WARN),

    /** Still running, already past its designed dwell. Raised live, not at the stop edge. */
    RUNNING_OVER_BUDGET("running_over_budget", BlockWarningSeverity.WARN),

    /** The clock, as it stands, would not place this boundary inside G4b's 250 ms T3 budget. */
    CLOCK_MISSES_T3_BUDGET("clock_misses_t3_budget", BlockWarningSeverity.WARN),

    /** The clock would not place this boundary inside G4a's 6 s budget either. */
    CLOCK_MISSES_ALL_TESTS_BUDGET("clock_misses_all_tests_budget", BlockWarningSeverity.WARN),

    /** The block ended for a reason other than the operator saying so. */
    NOT_CLOSED_BY_OPERATOR("not_closed_by_operator", BlockWarningSeverity.NOTE),
}

data class BlockWarning(
    val kind: BlockWarningKind,
    /** The sentence an operator reads on a bench. */
    val message: String,
) {
    val severity: BlockWarningSeverity get() = kind.severity
}

/**
 * The guard-rails.
 *
 * Split out from the state machine so each condition can be checked on its own, and so the console
 * can evaluate the live ones ([whileRunning]) on a display tick without touching session state.
 */
object BlockGuards {

    /**
     * Warn when the declared level and the live tally differ by at least this many people.
     *
     * One. The pre-registration's own E3 rule fires at a discrepancy of ≥ 1 person between the QR
     * tally and the manual sheet, and there is no reason for the block label to be held to a looser
     * standard than the ground truth it is asserting.
     */
    const val LEVEL_TALLY_TOLERANCE: Int = 1

    fun onStart(
        request: BlockStartRequest,
        tally: BlockTally?,
        clock: ClockStamp?,
    ): List<BlockWarning> = buildList {
        addAll(levelWarnings(request.kind, request.level))
        addAll(tallyWarnings(request.kind, request.level, tally))
        addAll(clockWarnings(clock))
    }

    fun onStop(
        block: ActiveBlock,
        durationMillis: Long,
        stopReason: BlockStopReason,
        tally: BlockTally?,
        clock: ClockStamp?,
    ): List<BlockWarning> = buildList {
        val min = block.kind.minSeconds
        val max = block.kind.maxSeconds
        if (min != null && durationMillis < min * 1000L) {
            add(
                BlockWarning(
                    BlockWarningKind.DURATION_UNDER_BUDGET,
                    "${block.kind.label} ran ${formatSeconds(durationMillis)} — the design says " +
                        "${block.kind.designedSeconds} s (floor ${min} s). Too short to be the " +
                        "condition it claims to be.",
                )
            )
        }
        if (max != null && durationMillis > max * 1000L) {
            add(
                BlockWarning(
                    BlockWarningKind.DURATION_OVER_BUDGET,
                    "${block.kind.label} ran ${formatSeconds(durationMillis)} — the design says " +
                        "${block.kind.designedSeconds} s (ceiling ${max} s). It has probably " +
                        "absorbed the next block.",
                )
            )
        }
        if (stopReason != BlockStopReason.OPERATOR) {
            add(
                BlockWarning(
                    BlockWarningKind.NOT_CLOSED_BY_OPERATOR,
                    "${block.kind.label} was ${stopReason.label} — its trailing edge is an artefact " +
                        "of that, not a judgement that the condition was over.",
                )
            )
        }
        addAll(tallyWarnings(block.kind, block.level, tally))
        addAll(clockWarnings(clock))
    }

    /** Evaluated on a display tick. Pure in (block, now). */
    fun whileRunning(block: ActiveBlock, nowMonotonicNanos: Long): List<BlockWarning> {
        val max = block.kind.maxSeconds ?: return emptyList()
        val elapsed = block.elapsedMillis(nowMonotonicNanos)
        if (elapsed <= max * 1000L) return emptyList()
        return listOf(
            BlockWarning(
                BlockWarningKind.RUNNING_OVER_BUDGET,
                "${block.kind.label} has been running ${formatSeconds(elapsed)} — past its " +
                    "${max} s ceiling. Stop it before it swallows the next one.",
            )
        )
    }

    private fun levelWarnings(kind: BlockKind, level: Int): List<BlockWarning> = buildList {
        val registered = kind.registeredLevels
        if (registered != null) {
            if (level !in registered) {
                add(
                    BlockWarning(
                        BlockWarningKind.LEVEL_OFF_PATTERN,
                        "${kind.label} at level $level — the frozen pattern uses " +
                            registered.joinToString("/") + ". Check the level before you start.",
                    )
                )
            }
        } else if (!LabStaircase.isRegistered(level)) {
            add(
                BlockWarning(
                    BlockWarningKind.LEVEL_OFF_STAIRCASE,
                    "level $level is not on the frozen staircase " +
                        LabStaircase.LEVELS.joinToString("/") + ".",
                )
            )
        }
    }

    private fun tallyWarnings(kind: BlockKind, level: Int, tally: BlockTally?): List<BlockWarning> =
        buildList {
            if (tally == null || !tally.isRoomWide) {
                add(
                    BlockWarning(
                        BlockWarningKind.TALLY_NOT_ROOM_WIDE,
                        "no room-wide tally to check the level against — this handset sees only " +
                            "its own scans, so nothing here confirms $level people are in $zoneWord.",
                    )
                )
                return@buildList
            }
            if (tally.source == TallySource.ROOM_STALE) {
                add(
                    BlockWarning(
                        BlockWarningKind.TALLY_STALE,
                        "the room tally is stale, so the level check below is weak evidence.",
                    )
                )
            }
            // SETUP carries no occupancy claim, so there is nothing to disagree with.
            if (kind == BlockKind.SETUP) return@buildList
            val delta = tally.count - level
            if (delta >= LEVEL_TALLY_TOLERANCE || delta <= -LEVEL_TALLY_TOLERANCE) {
                add(
                    BlockWarning(
                        BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY,
                        "you declared level $level but the room tally says ${tally.count}. " +
                            "A mislabelled block cannot be repaired afterwards — the label IS the " +
                            "ground truth the analysis uses. Count heads before you start.",
                    )
                )
            }
        }

    private fun clockWarnings(clock: ClockStamp?): List<BlockWarning> = buildList {
        if (clock == null) return@buildList
        if (!clock.meetsAllTestsBudget) {
            add(
                BlockWarning(
                    BlockWarningKind.CLOCK_MISSES_ALL_TESTS_BUDGET,
                    "clock gate G4a would fail (${clock.precisionLine}) — this boundary cannot be " +
                        "placed on the fleet timeline at all.",
                )
            )
        } else if (!clock.meetsT3Budget) {
            add(
                BlockWarning(
                    BlockWarningKind.CLOCK_MISSES_T3_BUDGET,
                    "clock gate G4b would fail (${clock.precisionLine}) — a 30 s cycling ramp " +
                        "cannot be labelled at this precision, so T3 would drop this fold.",
                )
            )
        }
    }

    private const val zoneWord = "the zone"

    internal fun formatSeconds(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    }
}
