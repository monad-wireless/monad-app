package dev.dubec.monad.quests.domain

import dev.dubec.monad.quests.domain.port.QuestStepRecord

/**
 * The wall-clock start and end a completion reports for one step, in epoch milliseconds.
 *
 * A step with no recorded start (the first step is inserted `in_progress` with none) starts at its
 * row's creation time. That time was written once at quest start. The fallback used to be the
 * active-quest screen's `startTime`, which resets on every load: after a restart and a resume it was
 * later than the first step's completion, the backend refused the whole completion with 400
 * `steps[0].started_at`, and every retry sent the same payload (monad-app#2).
 *
 * A step with no recorded end ends when the run ends.
 */
internal fun stepWindowMillis(step: QuestStepRecord, runEndedMillis: Long): Pair<Long, Long> =
    (step.startedAtMillis ?: step.createdAtMillis) to (step.completedAtMillis ?: runEndedMillis)
