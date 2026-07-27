package sk.martinvanco.monad.quests.presentation.quest_ended

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import io.ktor.http.encodeURLParameter
import sk.martinvanco.monad.main.presentation.MainContainerScreen

data class QuestEndedEarlyScreen(
    val questId: String,
    val enrollmentId: String,
    val userName: String,
    val startTime: Long
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        // Upload and submission are owned by QuestSessionCoordinator, invoked from
        // ActiveQuestScreenModel.submitQuest(). This screen is presentational — the third copy of
        // the export/upload/flush sequence lived here and had already drifted from the other two.
        val isUploading = false
        val uploadProgress = ""
        val uploadError: String? = null
        val uploadSuccess = true

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
                    // Success state - show "sorry" message
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
                            text = "We're sorry to hear that",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F142F),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Your data has been uploaded. If you experienced any issues, please let us know.",
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val encodedName = userName.encodeURLParameter()
                                val encodedEnrollmentId = enrollmentId.encodeURLParameter()
                                val formUrl = "https://docs.google.com/forms/d/e/1FAIpQLSflOpt5VhlLJQJBxGqJchGT9kumU0OG77zj_Qab-eIRGcxEzw/viewform?usp=pp_url&entry.2049278242=$encodedName&entry.1921053505=$encodedEnrollmentId"
                                navigator.replaceAll(MainContainerScreen())
                                uriHandler.openUri(formUrl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Report an Issue",
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
