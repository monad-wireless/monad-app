package sk.martinvanco.monad.lab.domain

/**
 * The block state machine.
 *
 * One block at a time, by construction. A phone cannot be in two experimental conditions at once,
 * and allowing overlapping blocks would make the marker stream ambiguous in exactly the way the
 * marker stream exists to prevent — so starting a block while one runs **auto-stops the previous
 * one and says so**, rather than refusing (which loses the new block) or nesting (which loses the
 * meaning of both).
 *
 * Pure: every transition is a function of (state, command, clock readings passed in). The instrument
 * owns the clock and the database; this owns the rules.
 */
object BlockMachine {

    /**
     * Open a block.
     *
     * @param sessionRunning false refuses outright — see [BlockRejection.NO_SESSION].
     */
    fun start(
        state: BlockSessionState,
        request: BlockStartRequest,
        sessionRunning: Boolean,
        monotonicNanos: Long,
        wallMillis: Long,
        tally: BlockTally?,
        clock: ClockStamp?,
    ): BlockCommandResult {
        if (!sessionRunning) return BlockCommandResult.Rejected(BlockRejection.NO_SESSION)
        if (request.zoneId.isBlank()) return BlockCommandResult.Rejected(BlockRejection.NO_ZONE)

        val marks = mutableListOf<BlockMark>()
        var next = state

        // A block left open when the next one starts is closed here rather than silently
        // overwritten: the previous condition really did end at this instant, and the analysis
        // needs its trailing edge as much as it needs the new leading edge.
        state.active?.let { running ->
            val closed = close(
                block = running,
                monotonicNanos = monotonicNanos,
                wallMillis = wallMillis,
                reason = BlockStopReason.SUPERSEDED,
                tally = tally,
                clock = clock,
            )
            marks += closed.second
            next = next.copy(active = null, last = closed.first)
        }

        val warnings = BlockGuards.onStart(request, tally, clock)
        val sequence = next.sequence + 1
        val opened = ActiveBlock(
            blockId = request.blockId,
            zoneId = request.zoneId,
            level = request.level,
            subCondition = request.subCondition,
            kind = request.kind,
            sequence = sequence,
            startedMonotonicNanos = monotonicNanos,
            startedWallMillis = wallMillis,
            startWarnings = warnings,
        )
        marks += BlockMark(
            phase = BlockPhase.START,
            blockId = opened.blockId,
            zoneId = opened.zoneId,
            level = opened.level,
            subCondition = opened.subCondition,
            kind = opened.kind,
            sequence = sequence,
            monotonicNanos = monotonicNanos,
            wallMillis = wallMillis,
            durationMillis = null,
            stopReason = null,
            tally = tally,
            clock = clock,
            warnings = warnings,
        )
        return BlockCommandResult.Applied(
            state = next.copy(active = opened, sequence = sequence),
            marks = marks,
        )
    }

    /**
     * Close the running block.
     *
     * [reason] is carried into the marker so the analysis can tell a deliberate close from one the
     * operator never made. [BlockStopReason.SESSION_END] is the automatic path: a session that ends
     * with a block open closes it here, and records that it did.
     */
    fun stop(
        state: BlockSessionState,
        reason: BlockStopReason,
        monotonicNanos: Long,
        wallMillis: Long,
        tally: BlockTally?,
        clock: ClockStamp?,
    ): BlockCommandResult {
        val running = state.active ?: return BlockCommandResult.Rejected(BlockRejection.NO_ACTIVE_BLOCK)
        val (completed, mark) = close(running, monotonicNanos, wallMillis, reason, tally, clock)
        return BlockCommandResult.Applied(
            state = state.copy(active = null, last = completed),
            marks = listOf(mark),
        )
    }

    private fun close(
        block: ActiveBlock,
        monotonicNanos: Long,
        wallMillis: Long,
        reason: BlockStopReason,
        tally: BlockTally?,
        clock: ClockStamp?,
    ): Pair<CompletedBlock, BlockMark> {
        val duration = block.elapsedMillis(monotonicNanos)
        val warnings = BlockGuards.onStop(block, duration, reason, tally, clock)
        val completed = CompletedBlock(
            blockId = block.blockId,
            zoneId = block.zoneId,
            level = block.level,
            subCondition = block.subCondition,
            kind = block.kind,
            sequence = block.sequence,
            startedMonotonicNanos = block.startedMonotonicNanos,
            startedWallMillis = block.startedWallMillis,
            endedMonotonicNanos = monotonicNanos,
            endedWallMillis = wallMillis,
            durationMillis = duration,
            stopReason = reason,
            warnings = warnings,
        )
        val mark = BlockMark(
            phase = BlockPhase.STOP,
            blockId = block.blockId,
            zoneId = block.zoneId,
            level = block.level,
            subCondition = block.subCondition,
            kind = block.kind,
            sequence = block.sequence,
            monotonicNanos = monotonicNanos,
            wallMillis = wallMillis,
            durationMillis = duration,
            stopReason = reason,
            tally = tally,
            clock = clock,
            warnings = warnings,
        )
        return completed to mark
    }
}

/**
 * Block state for one session.
 *
 * [sequence] counts blocks opened, and is carried onto every marker so a reader can order the
 * stream without trusting timestamps that came from two different epochs.
 */
data class BlockSessionState(
    val active: ActiveBlock? = null,
    val last: CompletedBlock? = null,
    val sequence: Int = 0,
) {
    val isRunning: Boolean get() = active != null

    /** Blocks opened in this session. */
    val opened: Int get() = sequence

    companion object {
        val EMPTY = BlockSessionState()
    }
}

sealed interface BlockCommandResult {
    /** Nothing was written. The reason is the whole result. */
    data class Rejected(val reason: BlockRejection) : BlockCommandResult

    /**
     * [marks] is ordered and may hold two entries: the auto-stop of a superseded block, then the
     * start of the new one. They are written in this order, on the same clock reading.
     */
    data class Applied(
        val state: BlockSessionState,
        val marks: List<BlockMark>,
    ) : BlockCommandResult

    val warnings: List<BlockWarning>
        get() = when (this) {
            is Rejected -> emptyList()
            is Applied -> marks.flatMap { it.warnings }
        }
}

/**
 * One block edge, ready to be written as a [SessionMarker].
 *
 * This is the wire contract, and every field here reaches `markers.tsv`. See
 * [BlockMarkerPayload] for the exact JSON the `payload_json` column carries.
 */
data class BlockMark(
    val phase: BlockPhase,
    val blockId: String,
    val zoneId: String,
    val level: Int,
    val subCondition: SubCondition,
    val kind: BlockKind,
    val sequence: Int,
    val monotonicNanos: Long,
    val wallMillis: Long,
    /** Stop edges only. */
    val durationMillis: Long?,
    /** Stop edges only. */
    val stopReason: BlockStopReason?,
    val tally: BlockTally?,
    val clock: ClockStamp?,
    val warnings: List<BlockWarning>,
) {
    val markerKind: SessionMarker.Kind
        get() = when (phase) {
            BlockPhase.START -> SessionMarker.Kind.BLOCK_START
            BlockPhase.STOP -> SessionMarker.Kind.BLOCK_STOP
        }

    /** The human line in the marker's `label` column and in the console log. */
    val label: String
        get() = buildString {
            append(zoneId).append(" L").append(level)
            append(' ').append(subCondition.wire)
            append(' ').append(kind.wire)
            append(' ').append(phase.wire)
            durationMillis?.let { append(" (").append(BlockGuards.formatSeconds(it)).append(')') }
        }
}
