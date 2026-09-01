package sk.martinvanco.monad.my_account.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.profile.presentation.ContributionSection
import sk.martinvanco.monad.profile.presentation.ProfileScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation

class MyAccountScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<MyAccountScreenModel>()
        val profileModel = koinScreenModel<ProfileScreenModel>()
        val profileState by profileModel.state.collectAsState()
        val state by screenModel.state.collectAsState()

        ScreenWithBackNavigation(
            title = "My account",
            onBackClick = { navigator.pop() }
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Scrolls, and no longer SpaceBetween. The screen used to hold an identity
                // block and two buttons, so pinning them to the ends was fine; it now carries
                // the contribution section between them and SpaceBetween would push the
                // destructive actions off the fold on a small handset.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(32.dp))

                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(Color(0xFFE0E7FF), shape = CircleShape)
                                .border(2.dp, Color(0xFF5B6ECC), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.userName?.firstOrNull()?.uppercase() ?: "U",
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5B6ECC)
                            )
                        }

                        state.userName?.let { userName ->
                            Text(
                                text = userName,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F142F),
                                textAlign = TextAlign.Center
                            )
                        }

                        Text(
                            text = state.userEmail,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }

                    // IP-145. The thing somebody opens this tab to look at, placed above the
                    // thing they open it to avoid.
                    ContributionSection(
                        stats = profileState.stats,
                        isLoading = profileState.isLoading,
                        error = profileState.error,
                        totalPoints = profileState.stats?.pointsTotal?.toInt() ?: 0,
                        siteUrl = AppConfig.SITE_URL,
                        onRetry = profileModel::load,
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = { screenModel.onEvent(MyAccountEvent.LogoutClick) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFDC2626)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Log out",
                                    tint = Color.White
                                )
                                Text(
                                    text = "Log out",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = { screenModel.onEvent(MyAccountEvent.DeleteAccountClick) }
                        ) {
                            Text(
                                text = "Delete my account",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            if (state.showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = {
                        if (!state.isDeleting) {
                            screenModel.onEvent(MyAccountEvent.DismissDeleteDialog)
                        }
                    },
                    title = {
                        Text(
                            text = "Delete Account",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Are you sure you want to delete your account? All your data will be permanently removed.")
                            state.deleteError?.let { error ->
                                Text(
                                    text = error,
                                    color = Color(0xFFDC2626),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { screenModel.onEvent(MyAccountEvent.ConfirmDeleteAccount) },
                            enabled = !state.isDeleting
                        ) {
                            if (state.isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Delete",
                                    color = Color(0xFFDC2626),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { screenModel.onEvent(MyAccountEvent.DismissDeleteDialog) },
                            enabled = !state.isDeleting
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
