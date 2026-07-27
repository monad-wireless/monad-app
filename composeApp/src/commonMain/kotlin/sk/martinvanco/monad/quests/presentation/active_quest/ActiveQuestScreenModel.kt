package sk.martinvanco.monad.quests.presentation.active_quest

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.StepCompletionRequestDto
import sk.martinvanco.monad.quests.data.dto.SkipRecordDto
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.quests.domain.QuestSessionCoordinator

class ActiveQuestScreenModel(
    private val sessionCoordinator: QuestSessionCoordinator,
    private val instrument: LabInstrument,
    private val questStepCompletionRepository: QuestStepCompletionRepository,
    private val questsService: QuestsService,
    private val userRepository: UserRepository,
    private val questId: String
) : StateScreenModel<ActiveQuestState>(ActiveQuestState(questId = questId)) {

    init {
        observeInstrument()
        initializeQuest()
    }

    private fun initializeQuest() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)

            try {
                // Get enrollment ID from user record
                val user = userRepository.getCurrentUser()
                if (user == null) {
                    showErrorWithoutCleanup("User not logged in")
                    return@launch
                }

                val enrollmentId = user.activeEnrollmentId
                if (enrollmentId.isNullOrEmpty()) {
                    // No enrollment - this shouldn't happen if activeQuestId is set
                    // Don't cleanup, let user retry or manually fix
                    showErrorWithoutCleanup("No active enrollment found. Please restart the quest.")
                    return@launch
                }

                // Load steps from local database by enrollment ID
                val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)

                if (steps.isEmpty()) {
                    // Steps not found - data might be corrupted, cleanup and navigate home
                    cleanupAndNavigateHome("No quest data found", enrollmentId, user.id)
                    return@launch
                }

                // Convert to ActiveTaskDto
                val tasks = steps.map { step ->
                    ActiveTaskDto(
                        name = step.stepName,
                        description = "",
                        type = try {
                            TaskType.valueOf(step.stepType.uppercase())
                        } catch (e: IllegalArgumentException) {
                            TaskType.INFO
                        },
                        status = when (step.status) {
                            "completed" -> TaskStatus.COMPLETED
                            "in_progress" -> TaskStatus.ACTIVE
                            "failed" -> TaskStatus.FAILED
                            else -> TaskStatus.SCHEDULED
                        },
                        config = step.stepConfig?.let {
                            try {
                                kotlinx.serialization.json.Json.parseToJsonElement(it)
                            } catch (e: Exception) {
                                null // Ignore invalid JSON config
                            }
                        }
                    )
                }

                // Check if all tasks completed
                val allCompleted = steps.isNotEmpty() && steps.all { it.status == "completed" }

                mutableState.value = mutableState.value.copy(
                    enrollmentId = enrollmentId,
                    questName = "Quest in Progress",
                    userName = user.name ?: "",
                    tasks = tasks,
                    isLoading = false,
                    startTime = currentTimeMillis(),
                    allTasksCompleted = allCompleted
                )

                // Start BLE sensing only after successful initialization
                startLabSession()
            } catch (e: CancellationException) {
                // Coroutine was cancelled - show error so user can retry
                showErrorWithoutCleanup("Quest loading was interrupted. Please try again.")
            } catch (e: Exception) {
                // Don't cleanup on general errors - might be temporary issue
                showErrorWithoutCleanup("Failed to load quest: ${e.message}")
            }
        }
    }

    private fun showErrorWithoutCleanup(errorMessage: String) {
        mutableState.value = mutableState.value.copy(
            isLoading = false,
            error = errorMessage
        )
    }

    private suspend fun cleanupAndNavigateHome(
        errorMessage: String,
        enrollmentId: String? = null,
        userId: Long? = null
    ) {
        // Abandon the quest but keep the measurement: a corrupt enrolment does not make the
        // radio session worthless, and the previous flush-everything path discarded it.
        runCatching { sessionCoordinator.abandonSession(enrollmentId) }

        mutableState.value = mutableState.value.copy(
            isLoading = false,
            error = errorMessage,
            shouldNavigateHome = true
        )
    }

    /**
     * A quest run *is* a lab session. Roles are chosen by the lab bundle: every participant
     * witnesses the surveyed anchors, and a phone that also has a traffic profile configured plays
     * the illuminator role as well.
     */
    private fun startLabSession() {
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            if (enrollmentId.isEmpty()) return@launch
            sessionCoordinator.startSession(questId, enrollmentId)
                .onFailure {
                    // Not fatal for the quest: the participant can still complete the steps, and
                    // the reason is recorded rather than swallowed.
                    mutableState.value = mutableState.value.copy(
                        error = "Instrument did not start: ${it.message}"
                    )
                }
        }
    }

    private fun observeInstrument() {
        instrument.state
            .onEach { instrumentState ->
                mutableState.value = mutableState.value.copy(
                    bleRecordCount = instrumentState.beaconCount,
                    isBleCollecting = instrumentState.isRunning,
                )
            }
            .launchIn(screenModelScope)
    }

    fun onEvent(event: ActiveQuestEvent) {
        when (event) {
            is ActiveQuestEvent.CompleteTask -> completeTask(event.taskIndex)
            is ActiveQuestEvent.FailTask -> failTask(event.taskIndex, event.reason)
            is ActiveQuestEvent.SubmitQuest -> submitQuest(success = true)
            is ActiveQuestEvent.RetryUpload -> retryUpload()
            is ActiveQuestEvent.RetryLoad -> initializeQuest()
            is ActiveQuestEvent.DismissCompletionError -> dismissCompletionError()
            is ActiveQuestEvent.DismissSuccessAndNavigateHome -> navigateHomeAfterSuccess()
            // End quest early events
            is ActiveQuestEvent.ShowEndQuestConfirmation -> showEndQuestConfirmation()
            is ActiveQuestEvent.DismissEndQuestConfirmation -> dismissEndQuestConfirmation()
            is ActiveQuestEvent.ConfirmEndQuest -> confirmEndQuest()
        }
    }

    private fun showEndQuestConfirmation() {
        mutableState.value = mutableState.value.copy(showEndQuestConfirmation = true)
    }

    private fun dismissEndQuestConfirmation() {
        mutableState.value = mutableState.value.copy(showEndQuestConfirmation = false)
    }

    private fun confirmEndQuest() {
        // The instrument keeps running until submitQuest() closes the session; ending the quest
        // early is a quest-state change, not a reason to truncate the measurement mid-flight.

        // Mark current active step as failed
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)
            val activeStep = steps.find { it.status == "in_progress" }

            activeStep?.let { step ->
                val currentTime = currentTimeMillis()
                questStepCompletionRepository.markStepFailed(
                    backendId = step.backendId,
                    completedAt = currentTime,
                    skipMessage = "Quest ended early by user",
                    skipErrorCode = "USER_CANCELLED"
                )
            }
        }

        // Navigate to ended early screen
        mutableState.value = mutableState.value.copy(
            showEndQuestConfirmation = false,
            navigateToEndedEarlyScreen = true
        )
    }

    private fun retryUpload() {
        submitQuest(success = true)
    }

    private fun dismissCompletionError() {
        mutableState.value = mutableState.value.copy(completionError = null)
    }

    private fun navigateHomeAfterSuccess() {
        mutableState.value = mutableState.value.copy(
            isCompleted = false,
            shouldNavigateHome = true
        )
    }

    private fun completeTask(taskIndex: Int) {
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)
            val step = steps.getOrNull(taskIndex) ?: return@launch

            val currentTime = currentTimeMillis()

            // Mark current step as completed
            questStepCompletionRepository.markStepCompleted(step.backendId, currentTime)

            // Mark next step as in_progress
            steps.getOrNull(taskIndex + 1)?.let { nextStep ->
                questStepCompletionRepository.updateStepStatus(nextStep.backendId, "in_progress", currentTime)
            }

            // Reload tasks from DB to update UI (this also computes allTasksCompleted)
            reloadTasks()
        }
    }

    private fun reloadTasks() {
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)
            val tasks = steps.map { step ->
                ActiveTaskDto(
                    name = step.stepName,
                    description = "",
                    type = try {
                        TaskType.valueOf(step.stepType.uppercase())
                    } catch (e: IllegalArgumentException) {
                        TaskType.INFO
                    },
                    status = when (step.status) {
                        "completed" -> TaskStatus.COMPLETED
                        "in_progress" -> TaskStatus.ACTIVE
                        "failed" -> TaskStatus.FAILED
                        else -> TaskStatus.SCHEDULED
                    },
                    config = step.stepConfig?.let {
                        kotlinx.serialization.json.Json.parseToJsonElement(it)
                    }
                )
            }
            val allCompleted = steps.isNotEmpty() && steps.all { it.status == "completed" }
            mutableState.value = mutableState.value.copy(
                tasks = tasks,
                allTasksCompleted = allCompleted
            )
        }
    }

    /**
     * Close the run: stop the instrument, submit the completion, upload every unsynced session,
     * and purge only what the server acknowledged.
     *
     * The export/upload/flush sequence used to live here *and* in the completed screen *and* in
     * the abandoned screen, in three copies that had already drifted apart. It now lives once, in
     * [QuestSessionCoordinator].
     */
    private fun submitQuest(success: Boolean, failReason: String? = null) {
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            if (enrollmentId.isEmpty()) {
                mutableState.value = mutableState.value.copy(completionError = "No active enrollment")
                return@launch
            }

            mutableState.value = mutableState.value.copy(
                isUploading = true,
                uploadProgress = "Closing session..."
            )

            val outcome = sessionCoordinator.finishSession(
                questId = questId,
                enrollmentId = enrollmentId,
                startedWallMillis = mutableState.value.startTime,
                completed = success,
                skip = failReason?.let { SkipRecordDto(message = it, errorCode = null) },
            )

            mutableState.value = if (outcome.completionSubmitted) {
                mutableState.value.copy(
                    isUploading = false,
                    navigateToCompletedScreen = true,
                )
            } else {
                mutableState.value.copy(
                    isUploading = false,
                    // Data is retained locally; the lab console shows it as unsynced and can retry.
                    completionError = "Not submitted (${outcome.completionError ?: "unknown"}). " +
                        "${outcome.sessionsUnsynced} session(s) kept on device.",
                )
            }
        }
    }
    private fun failTask(taskIndex: Int, reason: String) {
        screenModelScope.launch {
            val enrollmentId = mutableState.value.enrollmentId
            val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)
            val step = steps.getOrNull(taskIndex) ?: return@launch

            val currentTime = currentTimeMillis()
            questStepCompletionRepository.markStepFailed(
                backendId = step.backendId,
                completedAt = currentTime,
                skipMessage = reason,
                skipErrorCode = null
            )

            reloadTasks()
        }
    }

    override fun onDispose() {
        super.onDispose()
        // The session is deliberately NOT stopped here. Leaving the screen must not end a
        // measurement — the whole point of the instrument is that it keeps running while the app
        // is backgrounded. Only submitQuest() closes a session.
    }
}
