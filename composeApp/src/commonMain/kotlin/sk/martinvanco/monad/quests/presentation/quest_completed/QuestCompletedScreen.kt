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
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.main.presentation.MainContainerScreen

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
        // IP-157 — the permission moment. Owns only the pre-prompt card's visibility.
        val promptModel = koinScreenModel<QuestCompletedScreenModel>()
        val promptState by promptModel.state.collectAsState()

        // Upload and submission are owned by QuestSessionCoordinator, invoked from
        // ActiveQuestScreenModel.submitQuest(). This screen is presentational: duplicating the
        // sequence here is what let the three copies drift, and one of them deleted local data on
        // a failed upload.
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
                        if (promptState.showNotificationPrompt) {
                            NotificationPrePrompt(
                                onAllow = { promptModel.onEvent(QuestCompletedEvent.AllowNotifications) },
                                onNotNow = { promptModel.onEvent(QuestCompletedEvent.NotNow) },
                            )
                        }
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

    /**
     * One sentence before the OS dialog (IP-157). Shown once, here, after the first completed
     * quest, never on launch: the participant has just done the thing a callout would ask for again,
     * so the question is one they can answer. "Not now" leaves the ask to the settings screen.
     */
    @Composable
    private fun NotificationPrePrompt(onAllow: () -> Unit, onNotNow: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFEEF1FB))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Want to know when the lab needs a measurement?",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F142F)
            )
            Text(
                text = "A short notification when a quest is worth walking. Off any time in My account.",
                fontSize = 13.sp,
                color = Color(0xFF475569)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Allow", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onNotNow) {
                    Text("Not now", color = Color(0xFF475569))
                }
            }
        }
    }
}
