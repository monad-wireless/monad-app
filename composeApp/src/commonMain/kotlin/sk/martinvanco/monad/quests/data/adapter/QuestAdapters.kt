package sk.martinvanco.monad.quests.data.adapter

import kotlinx.serialization.json.Json
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.SkipRecordDto
import sk.martinvanco.monad.quests.data.dto.StepCompletionRequestDto
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.port.LabBundleSource
import sk.martinvanco.monad.quests.domain.port.LabSessionArchive
import sk.martinvanco.monad.quests.domain.port.ParticipantDirectory
import sk.martinvanco.monad.quests.domain.port.QuestCompletion
import sk.martinvanco.monad.quests.domain.port.QuestCompletionGateway
import sk.martinvanco.monad.quests.domain.port.QuestParticipant
import sk.martinvanco.monad.quests.domain.port.QuestStepJournal
import sk.martinvanco.monad.quests.domain.port.QuestStepRecord

/**
 * The adapters behind `quests/domain/port`.
 *
 * They live here, in the *quests* feature's data layer, rather than on the services they wrap. A
 * `LabConfigService` that implemented a `quests.domain` interface would make the lab feature depend
 * on the quest feature to satisfy the quest feature's own boundary — the dependency would still
 * cross, only backwards and less visibly. Each adapter is the whole translation: nothing above them
 * names a DTO, a SQLDelight row or a Ktor service, and nothing below them knows a quest exists.
 *
 * They are deliberately thin. Every one of them is a rename plus, where the wire demands it, a
 * parse — no decisions. The decisions are in `QuestSessionCoordinator`, which is the point.
 */

/** [LabConfigService] as the quest path needs it: loaded?, current, load, refresh. */
class LabBundleSourceAdapter(
    private val service: LabConfigService,
) : LabBundleSource {

    override val isLoaded: Boolean
        get() = service.source.value != LabConfigService.Source.NONE

    override val current: LabConfig get() = service.config.value

    override suspend fun loadCached() {
        service.loadCached()
    }

    override suspend fun refresh(token: String?) {
        // The service already logs its own failure and falls back to whatever is loaded; a phone
        // about to join an experiment AP frequently has no route to the backend, and that must not
        // stop a session from starting.
        service.refresh(token)
    }
}

/**
 * The local session backlog, drained through [LabSessionUploader].
 *
 * `purgeAfter = true` is applied here rather than being a parameter of the port: the rule is
 * *upload, then purge only what the server acknowledged*, and it is the uploader that enforces it.
 * A port that let the caller choose would be a port that let the caller get it wrong.
 */
class LabSessionArchiveAdapter(
    private val uploader: LabSessionUploader,
    private val sessions: LabSessionRepository,
) : LabSessionArchive {

    override suspend fun uploadPending(): Int = uploader.uploadPending(purgeAfter = true)

    override suspend fun unsyncedCount(): Long = sessions.unsyncedCount()
}

/** [UserRepository] narrowed to the two facts a quest run needs. */
class ParticipantDirectoryAdapter(
    private val users: UserRepository,
) : ParticipantDirectory {

    override suspend fun current(): QuestParticipant? = users.getCurrentUser()?.let { user ->
        QuestParticipant(
            userId = user.id,
            // The backend id when the account has one, else the local row id. The dataset never
            // carries the e-mail.
            participantId = user.backendId ?: user.id.toString(),
            token = user.token,
        )
    }

    override suspend fun clearActiveQuest(userId: Long) = users.clearActiveQuestId(userId)
}

/** Domain completion → the backend's `QuestCompleteRequestDto`. */
class QuestCompletionGatewayAdapter(
    private val service: QuestsService,
) : QuestCompletionGateway {

    override suspend fun submitCompletion(questId: String, completion: QuestCompletion, token: String) {
        service.completeQuest(
            questId = questId,
            request = QuestCompleteRequestDto(
                enrollmentId = completion.enrollmentId,
                completedAt = completion.completedAtIso,
                steps = completion.steps.map { step ->
                    StepCompletionRequestDto(
                        stepCompletionId = step.stepCompletionId,
                        status = step.status,
                        startedAt = step.startedAtIso,
                        completedAt = step.completedAtIso,
                        // Unparseable step data is dropped rather than allowed to fail the whole
                        // completion: the radio measurement is the expensive artefact, and a step's
                        // free-form payload must never be able to strand it.
                        stepData = step.stepDataJson
                            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
                        skipRecord = step.skip?.let {
                            SkipRecordDto(message = it.message, errorCode = it.errorCode)
                        },
                    )
                },
            ),
            token = token,
        )
    }
}

/** [QuestStepCompletionRepository] narrowed to read-and-clear. */
class QuestStepJournalAdapter(
    private val steps: QuestStepCompletionRepository,
) : QuestStepJournal {

    override suspend fun stepsFor(enrollmentId: String): List<QuestStepRecord> =
        steps.getByEnrollmentId(enrollmentId).map { row ->
            QuestStepRecord(
                stepCompletionId = row.backendId,
                status = row.status,
                startedAtMillis = row.startedAt,
                completedAtMillis = row.completedAt,
                stepDataJson = row.stepData,
                skipMessage = row.skipMessage,
                skipErrorCode = row.skipErrorCode,
            )
        }

    override suspend fun clear(enrollmentId: String) = steps.deleteByEnrollmentId(enrollmentId)
}
