package sk.martinvanco.monad.quests.presentation.quest_completed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.datetime.Instant
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.StepCompletionRequestDto
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.QuestDataExportService
import sk.martinvanco.monad.quests.domain.QuestDataFlushService
import sk.martinvanco.monad.main.presentation.MainContainerScreen
import sk.martinvanco.monad.storage.data.api.StorageService

data class QuestCompletedScreen(
    val questId: String,
    val enrollmentId: String,
    val userName: String,
    val startTime: Long,
    val uploadAlreadyCompleted: Boolean = false
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        // If upload already completed, skip to success state
        var isUploading by remember { mutableStateOf(!uploadAlreadyCompleted) }
        var uploadProgress by remember { mutableStateOf("Preparing data...") }
        var uploadError by remember { mutableStateOf<String?>(null) }
        var uploadSuccess by remember { mutableStateOf(uploadAlreadyCompleted) }

        // Only upload if not already completed
        if (!uploadAlreadyCompleted) {
            // Get dependencies
            val questStepCompletionRepository: QuestStepCompletionRepository = remember { getKoin().get() }
            val questDataExportService: QuestDataExportService = remember { getKoin().get() }
            val questDataFlushService: QuestDataFlushService = remember { getKoin().get() }
            val questsService: QuestsService = remember { getKoin().get() }
            val storageService: StorageService = remember { getKoin().get() }
            val userRepository: UserRepository = remember { getKoin().get() }

            // Upload data on screen launch
            LaunchedEffect(Unit) {
                try {
                    val user = userRepository.getCurrentUser()
                        ?: throw Exception("User not logged in")
                    val token = user.token ?: throw Exception("No auth token")

                    // 1. Generate and upload BLE data
                    uploadProgress = "Uploading BLE data..."
                    val bleData = questDataExportService.generateBleDataTsv(questId)
                    val bleCount = questDataExportService.getBleRecordCount(questId)

                    storageService.uploadExperimentFile(
                        filename = "ble_data.tsv",
                        experimentId = enrollmentId,
                        content = bleData,
                        token = token
                    )

                    // 2. Generate and upload metadata
                    uploadProgress = "Uploading metadata..."
                    val endTimeMillis = currentTimeMillis()
                    val startTimeFormatted = Instant.fromEpochMilliseconds(startTime).toString()
                    val endTimeFormatted = Instant.fromEpochMilliseconds(endTimeMillis).toString()

                    val metadata = questDataExportService.generateMetadataTsv(
                        questId = questId,
                        enrollmentId = enrollmentId,
                        startTime = startTimeFormatted,
                        endTime = endTimeFormatted,
                        status = "completed",
                        totalBleRecords = bleCount
                    )

                    storageService.uploadExperimentFile(
                        filename = "metadata.tsv",
                        experimentId = enrollmentId,
                        content = metadata,
                        token = token
                    )

                    // 3. Send completion to backend
                    uploadProgress = "Completing quest..."
                    val steps = questStepCompletionRepository.getByEnrollmentId(enrollmentId)

                    val stepCompletions = steps.map { step ->
                        StepCompletionRequestDto(
                            stepCompletionId = step.backendId,
                            status = "completed",
                            startedAt = step.startedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: startTimeFormatted,
                            completedAt = step.completedAt?.let { Instant.fromEpochMilliseconds(it).toString() } ?: endTimeFormatted,
                            stepData = step.stepData?.let {
                                kotlinx.serialization.json.Json.parseToJsonElement(it)
                            },
                            skipRecord = null
                        )
                    }

                    val completeRequest = QuestCompleteRequestDto(
                        enrollmentId = enrollmentId,
                        completedAt = endTimeFormatted,
                        steps = stepCompletions
                    )

                    questsService.completeQuest(questId, completeRequest, token)

                    // 4. Flush local data
                    uploadProgress = "Cleaning up..."
                    questDataFlushService.flushQuestData(enrollmentId, questId, user.id)

                    // 5. Done
                    isUploading = false
                    uploadSuccess = true

                } catch (e: Exception) {
                    isUploading = false
                    uploadError = "Upload failed: ${e.message}"
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isUploading -> {
                    // Loading state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF22C55E),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Uploading Data",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F142F)
                        )
                        Text(
                            text = uploadProgress,
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                uploadError != null -> {
                    // Error state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFEE2E2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = "Error",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Upload Failed",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F142F)
                        )
                        Text(
                            text = uploadError ?: "Unknown error",
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { navigator.replaceAll(MainContainerScreen()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Return Home", color = Color.White)
                        }
                    }
                }
                uploadSuccess -> {
                    // Success state - thank the user
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDCFCE7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Success",
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Quest Completed!",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F142F),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Thank you for participating! Your data has been uploaded successfully.",
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val formUrl = "https://docs.google.com/forms/d/e/1FAIpQLScC60bVWt-5Vyg1jB45PgAgCmsPqW5nHIhgoXPqaLVZgrknRA/viewform?usp=publish-editor"
                                uriHandler.openUri(formUrl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Leave Feedback",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        TextButton(
                            onClick = { navigator.replaceAll(MainContainerScreen()) }
                        ) {
                            Text(
                                text = "Return Home",
                                color = Color(0xFF6B7280)
                            )
                        }
                    }
                }
            }
        }
    }
}
