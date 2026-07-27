package sk.martinvanco.monad.quests.domain

import io.github.aakira.napier.Napier
import kotlinx.datetime.Instant
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionRequest
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.SkipRecordDto
import sk.martinvanco.monad.quests.data.dto.StepCompletionRequestDto
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository

/**
 * The one place a quest becomes a lab session, and the one place a finished quest is uploaded.
 *
 * Previously this logic existed three times — in the active-quest model, the completed screen, and
 * the abandoned screen — each re-deriving the same export → upload → *flush anyway* sequence. Two
 * consequences followed from the duplication: the three copies drifted (only one of them recorded a
 * skip record), and all three deleted local data on paths where the upload had failed, discarding
 * the only copy of a session.
 *
 * This class enforces the replacement rule: **submit, upload, and only then purge.** A failure at
 * any stage leaves every byte on disk and the session visible as unsynced in the lab console.
 */
class QuestSessionCoordinator(
    private val instrument: LabInstrument,
    private val labConfig: LabConfigService,
    private val labSessions: LabSessionRepository,
    private val uploader: LabSessionUploader,
    private val questSteps: QuestStepCompletionRepository,
    private val questsService: QuestsService,
    private val users: UserRepository,
) {

    /**
     * Start instrumenting a quest.
     *
     * A quest run is a witness session by default and additionally an illuminator session when the
     * lab bundle carries a traffic profile and the quest's AP is known — so an ordinary participant
     * contributes zone truth, while an operator running a rate-ladder contributes illumination too.
     */
    suspend fun startSession(
        questId: String,
        enrollmentId: String,
        apId: String? = null,
        profileId: String? = null,
    ): Result<String> {
        val config = labConfig.config.value
        val user = users.getCurrentUser()
            ?: return Result.failure(IllegalStateException("not logged in"))

        val profile = profileId?.let { config.trafficProfile(it) }
        val accessPoint = apId?.let { config.accessPoint(it) }
            ?: profile?.apId?.let { config.accessPoint(it) }
        val emit = profile != null && config.isIlluminationReady

        return instrument.start(
            SessionRequest(
                // The dataset carries the pseudonym, never the account e-mail: the game owns the
                // account, the measurement owns only the participant key.
                participantId = user.backendId ?: user.id.toString(),
                collector = config.collector,
                beacons = config.beacons,
                accessPoint = accessPoint,
                trafficProfile = profile,
                clockSync = config.clockSync,
                site = config.site,
                configVersion = config.version,
                enrollmentId = enrollmentId,
                questId = questId,
                emit = emit,
                witness = config.beacons.isConfigured,
            )
        )
    }

    /**
     * Close the instrument, tell the backend the quest is over, upload the artefacts, and purge
     * only what the server acknowledged.
     *
     * [skip] is non-null when the participant abandoned or a step failed — the same path handles
     * both outcomes, because from the data's point of view an abandoned session is a session with
     * a different status, not a session to be thrown away.
     */
    suspend fun finishSession(
        questId: String,
        enrollmentId: String,
        startedWallMillis: Long,
        completed: Boolean,
        skip: SkipRecordDto? = null,
    ): FinishOutcome {
        val sessionId = instrument.stop().getOrNull()

        val token = users.getCurrentUser()?.token
        val endedWallMillis = currentTimeMillis()
        val startIso = Instant.fromEpochMilliseconds(startedWallMillis).toString()
        val endIso = Instant.fromEpochMilliseconds(endedWallMillis).toString()

        var submitted = false
        var submitError: String? = null
        if (token != null) {
            runCatching {
                val steps = questSteps.getByEnrollmentId(enrollmentId)
                val stepCompletions = steps.map { step ->
                    // The backend accepts completed / failed / skipped only. A step the
                    // participant never reached is *skipped*; one they were standing on when the
                    // quest ended is *failed* — the distinction matters because only the second
                    // says something went wrong.
                    val mappedStatus = when (step.status) {
                        "completed" -> "completed"
                        "failed" -> "failed"
                        "skipped" -> "skipped"
                        "pending" -> "skipped"
                        "in_progress" -> "failed"
                        else -> "skipped"
                    }
                    val skipRecord = when (step.status) {
                        "failed", "skipped" -> skip ?: SkipRecordDto(
                            message = step.skipMessage ?: "Unknown reason",
                            errorCode = step.skipErrorCode,
                        )

                        "pending" -> SkipRecordDto(
                            message = "Quest ended before this step was reached",
                            errorCode = QUEST_ENDED_EARLY,
                        )

                        "in_progress" -> SkipRecordDto(
                            message = "Quest ended while this step was in progress",
                            errorCode = QUEST_ENDED_EARLY,
                        )

                        else -> null
                    }
                    StepCompletionRequestDto(
                        stepCompletionId = step.backendId,
                        status = mappedStatus,
                        startedAt = step.startedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: startIso,
                        completedAt = step.completedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: endIso,
                        stepData = step.stepData?.let {
                            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull()
                        },
                        skipRecord = skipRecord,
                    )
                }
                questsService.completeQuest(
                    questId = questId,
                    request = QuestCompleteRequestDto(
                        enrollmentId = enrollmentId,
                        completedAt = endIso,
                        steps = stepCompletions,
                    ),
                    token = token,
                )
            }.onSuccess { submitted = true }
                .onFailure {
                    submitError = it.message
                    Napier.w("[quest] completion submit failed: ${it.message}")
                }
        } else {
            submitError = "not authenticated"
        }

        // Upload every unsynced session, not just this one: a phone that spent a day offline in a
        // pocket is the normal case, and the moment it has connectivity is the moment to drain the
        // backlog.
        val uploaded = uploader.uploadPending(purgeAfter = true)

        // Local step rows are only dropped once the completion actually reached the server.
        if (submitted) {
            questSteps.deleteByEnrollmentId(enrollmentId)
            users.getCurrentUser()?.let { users.clearActiveQuestId(it.id) }
        }

        val unsynced = labSessions.unsyncedCount()
        return FinishOutcome(
            sessionId = sessionId,
            completionSubmitted = submitted,
            completionError = submitError,
            sessionsUploaded = uploaded,
            sessionsUnsynced = unsynced,
            completed = completed,
        )
    }

    /** Drain the upload backlog without touching quest state — used by the lab console. */
    suspend fun retryUploads(): Int = uploader.uploadPending(purgeAfter = true)

    suspend fun unsyncedSessions(): Long = labSessions.unsyncedCount()

    /**
     * Abandon a run whose local state is unusable (corrupt enrolment, missing steps).
     *
     * Distinct from [finishSession]: nothing is submitted, but the lab session is still closed and
     * its data still queued for upload. A broken quest does not make the radio measurement
     * worthless, and the old flush-everything path threw it away.
     */
    suspend fun abandonSession(enrollmentId: String?) {
        instrument.stop()
        enrollmentId?.let { questSteps.deleteByEnrollmentId(it) }
        users.getCurrentUser()?.let { users.clearActiveQuestId(it.id) }
        runCatching { uploader.uploadPending(purgeAfter = true) }
    }

    private companion object {
        const val QUEST_ENDED_EARLY = "QUEST_ENDED_EARLY"
    }
}

data class FinishOutcome(
    val sessionId: String?,
    val completionSubmitted: Boolean,
    val completionError: String?,
    val sessionsUploaded: Int,
    val sessionsUnsynced: Long,
    val completed: Boolean,
) {
    /** True when nothing is left on the device waiting for a network. */
    val isFullySynced: Boolean get() = completionSubmitted && sessionsUnsynced == 0L
}
