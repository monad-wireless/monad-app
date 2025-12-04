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
import sk.martinvanco.monad.ble.domain.BleSensingService
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.StepCompletionRequestDto
import sk.martinvanco.monad.quests.data.dto.SkipRecordDto
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.QuestDataExportService
import sk.martinvanco.monad.quests.domain.QuestDataFlushService
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType
import sk.martinvanco.monad.storage.data.api.StorageService

class ActiveQuestScreenModel(
    private val bleSensingService: BleSensingService,
    private val questStepCompletionRepository: QuestStepCompletionRepository,
    private val questDataExportService: QuestDataExportService,
    private val questDataFlushService: QuestDataFlushService,
    private val questsService: QuestsService,
    private val storageService: StorageService,
    private val userRepository: UserRepository,
    private val questId: String
) : StateScreenModel<ActiveQuestState>(ActiveQuestState(questId = questId)) {

    init {
        observeBleRecordCount()
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
                    tasks = tasks,
                    isLoading = false,
                    startTime = currentTimeMillis(),
                    allTasksCompleted = allCompleted
                )

                // Start BLE sensing only after successful initialization
                startBleSensing()
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
        // Try to clean up data, but don't let cleanup failures block navigation
        try {
            if (enrollmentId != null && userId != null) {
                questDataFlushService.flushQuestData(enrollmentId, questId, userId)
            } else {
                questDataFlushService.flushAllQuestData()
            }
        } catch (e: Exception) {
            // Ignore cleanup errors - navigation is more important
        }

        mutableState.value = mutableState.value.copy(
            isLoading = false,
            error = errorMessage,
            shouldNavigateHome = true
        )
    }

    private fun startBleSensing() {
        screenModelScope.launch {
            // Try to start BLE sensing, retry once if it fails due to "already collecting"
            var result = bleSensingService.startSensing(questId)
            if (result.isFailure) {
                // If already collecting (stale state), stop and retry
                bleSensingService.stopSensing()
                result = bleSensingService.startSensing(questId)
            }
            if (result.isSuccess) {
                mutableState.value = mutableState.value.copy(isBleCollecting = true)
            }
        }
    }

    private fun observeBleRecordCount() {
        bleSensingService.recordCount
            .onEach { count ->
                mutableState.value = mutableState.value.copy(bleRecordCount = count)
            }
            .launchIn(screenModelScope)

        bleSensingService.isCollecting
            .onEach { isCollecting ->
                mutableState.value = mutableState.value.copy(isBleCollecting = isCollecting)
            }
            .launchIn(screenModelScope)
    }

    fun onEvent(event: ActiveQuestEvent) {
        when (event) {
            is ActiveQuestEvent.CompleteTask -> completeTask(event.taskIndex)
            is ActiveQuestEvent.FailTask -> failTask(event.taskIndex, event.reason)
            is ActiveQuestEvent.EndQuestEarly -> endQuestEarly()
            is ActiveQuestEvent.SubmitQuest -> submitQuest(success = true)
            is ActiveQuestEvent.RetryUpload -> submitQuest(success = true)
            is ActiveQuestEvent.RetryLoad -> initializeQuest()
            is ActiveQuestEvent.DismissCompletionError -> dismissCompletionError()
            is ActiveQuestEvent.DismissSuccessAndNavigateHome -> navigateHomeAfterSuccess()
        }
    }

    private fun dismissCompletionError() {
        mutableState.value = mutableState.value.copy(completionError = null)
    }

    private fun navigateHomeAfterSuccess() {
        mutableState.value = mutableState.value.copy(shouldNavigateHome = true)
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

    private fun submitQuest(success: Boolean, failReason: String? = null) {
        screenModelScope.launch {
            // Stop BLE sensing immediately to ensure clean data cutoff
            stopBleSensing()

            mutableState.value = mutableState.value.copy(
                isUploading = true,
                uploadProgress = "Preparing data..."
            )

            try {
                val user = userRepository.getCurrentUser()
                    ?: throw Exception("User not logged in")
                val token = user.token ?: throw Exception("No auth token")
                val enrollmentId = mutableState.value.enrollmentId

                if (enrollmentId.isEmpty()) {
                    throw Exception("No active enrollment")
                }

                // 1. Generate and upload BLE data
                mutableState.value = mutableState.value.copy(uploadProgress = "Uploading BLE data...")
                val bleData = questDataExportService.generateBleDataTsv(questId)
                val bleCount = questDataExportService.getBleRecordCount(questId)

                storageService.uploadExperimentFile(
                    filename = "ble_data.tsv",
                    experimentId = enrollmentId,
                    content = bleData,
                    token = token
                )

                // 2. Generate and upload metadata
                mutableState.value = mutableState.value.copy(uploadProgress = "Uploading metadata...")
                val endTimeMillis = currentTimeMillis()
                val startTimeFormatted = Instant.fromEpochMilliseconds(mutableState.value.startTime).toString()
                val endTimeFormatted = Instant.fromEpochMilliseconds(endTimeMillis).toString()

                val metadata = questDataExportService.generateMetadataTsv(
                    questId = questId,
                    enrollmentId = enrollmentId,
                    startTime = startTimeFormatted,
                    endTime = endTimeFormatted,
                    status = if (success) "completed" else "failed",
                    totalBleRecords = bleCount
                )

                storageService.uploadExperimentFile(
                    filename = "metadata.tsv",
                    experimentId = enrollmentId,
                    content = metadata,
                    token = token
                )

                // 3. Send completion to backend
                mutableState.value = mutableState.value.copy(uploadProgress = "Completing quest...")
                val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)

                val stepCompletions = steps.map { step ->
                    // Map status to backend-accepted values: completed, failed, skipped
                    val mappedStatus = when (step.status) {
                        "completed" -> "completed"
                        "failed" -> "failed"
                        "skipped" -> "skipped"
                        "pending" -> "skipped"  // Never started
                        "in_progress" -> "failed"  // Started but not completed
                        else -> "skipped"
                    }

                    // Create skip record for non-completed steps
                    val skipRecord = when {
                        step.status == "failed" || step.status == "skipped" -> {
                            SkipRecordDto(
                                message = step.skipMessage ?: "Unknown reason",
                                errorCode = step.skipErrorCode
                            )
                        }
                        step.status == "pending" -> {
                            SkipRecordDto(
                                message = "Quest ended before this step was reached",
                                errorCode = "QUEST_ENDED_EARLY"
                            )
                        }
                        step.status == "in_progress" -> {
                            SkipRecordDto(
                                message = "Quest ended while this step was in progress",
                                errorCode = "QUEST_ENDED_EARLY"
                            )
                        }
                        else -> null
                    }

                    StepCompletionRequestDto(
                        stepCompletionId = step.backendId,
                        status = mappedStatus,
                        startedAt = step.startedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: startTimeFormatted,
                        completedAt = step.completedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: endTimeFormatted,
                        stepData = step.stepData?.let {
                            kotlinx.serialization.json.Json.parseToJsonElement(it)
                        },
                        skipRecord = skipRecord
                    )
                }

                val completeRequest = QuestCompleteRequestDto(
                    enrollmentId = enrollmentId,
                    completedAt = endTimeFormatted,
                    steps = stepCompletions
                )

                questsService.completeQuest(questId, completeRequest, token)

                // 4. Flush local data
                mutableState.value = mutableState.value.copy(uploadProgress = "Cleaning up...")
                questDataFlushService.flushQuestData(enrollmentId, questId, user.id)

                // 5. Done!
                mutableState.value = mutableState.value.copy(
                    isUploading = false,
                    isCompleted = true
                )

            } catch (e: ClientRequestException) {
                if (e.response.status.value == 404) {
                    // Enrollment not found - clean up local data and navigate home
                    val user = userRepository.getCurrentUser()
                    val enrollmentId = mutableState.value.enrollmentId
                    if (user != null && enrollmentId.isNotEmpty()) {
                        questDataFlushService.flushQuestData(enrollmentId, questId, user.id)
                    }
                    mutableState.value = mutableState.value.copy(
                        isUploading = false,
                        shouldNavigateHome = true
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        isUploading = false,
                        completionError = "Upload failed: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isUploading = false,
                    completionError = "Upload failed: ${e.message}"
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

    private fun endQuestEarly() {
        screenModelScope.launch {
            // Stop BLE sensing immediately
            stopBleSensing()

            // Mark current active step as failed
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

            // Submit with failure status
            submitQuest(success = false, failReason = "Quest ended early")
        }
    }

    private fun stopBleSensing() {
        bleSensingService.stopSensing()
    }

    override fun onDispose() {
        super.onDispose()
        stopBleSensing()
    }
}
