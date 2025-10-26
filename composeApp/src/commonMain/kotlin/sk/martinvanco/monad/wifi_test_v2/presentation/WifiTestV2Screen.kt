package sk.martinvanco.monad.wifi_test_v2.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel

class WifiTestV2Screen : Screen {

    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<WifiTestV2ScreenModel>()
        val state by screenModel.state.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "WiFi Connection Test V2",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                "Custom Implementation with Better Error Handling",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            HorizontalDivider()

            // Current Network Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Current Network",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        state.currentNetwork ?: "Not connected",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = { screenModel.onEvent(WifiTestV2Event.CheckCurrentNetwork) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Refresh")
                    }
                }
            }

            // SSID Input
            OutlinedTextField(
                value = state.ssid,
                onValueChange = { screenModel.onEvent(WifiTestV2Event.UpdateSsid(it)) },
                label = { Text("Network SSID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConnecting,
                singleLine = true
            )

            // Password Input
            OutlinedTextField(
                value = state.password,
                onValueChange = { screenModel.onEvent(WifiTestV2Event.UpdatePassword(it)) },
                label = { Text("Password (leave empty for open networks)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConnecting,
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { screenModel.onEvent(WifiTestV2Event.ConnectToNetwork) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isConnecting && state.ssid.isNotEmpty()
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isConnecting) "Connecting..." else "Connect")
                }

                OutlinedButton(
                    onClick = { screenModel.onEvent(WifiTestV2Event.DisconnectFromNetwork) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isConnecting
                ) {
                    Text("Disconnect")
                }
            }

            // Status Message
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        state.lastError != null -> MaterialTheme.colorScheme.errorContainer
                        state.statusMessage.startsWith("✓") -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Status",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (state.statusMessage.isNotEmpty()) {
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (state.lastError != null) {
                        Text(
                            "Error: ${state.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Information Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "About This Implementation",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "• Custom WiFi service with detailed error handling\n" +
                        "• Properly detects wrong password, network not found, user cancellation\n" +
                        "• Platform-specific implementations for Android and iOS\n" +
                        "• Better timeout handling and connection verification",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
