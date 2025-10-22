package sk.martinvanco.monad.wifi_test.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import sk.martinvanco.monad.ui.theme.h1
import sk.martinvanco.monad.ui.theme.h3

class WifiTestScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<WifiTestScreenModel>()
        val state by screenModel.state.collectAsState()

        WifiTestContent(
            state = state,
            onEvent = screenModel::onEvent
        )
    }
}

@Composable
private fun WifiTestContent(
    state: WifiTestState,
    onEvent: (WifiTestEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WiFi Connection Test",
            style = MaterialTheme.typography.h1
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Platform warning
        if (state.platformWarning.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = state.platformWarning,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick connect to Ynet
        Text(
            text = "Quick Connect",
            style = MaterialTheme.typography.h3
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onEvent(WifiTestEvent.ConnectToYnet) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isConnecting
        ) {
            if (state.isConnecting && state.currentNetwork == "Ynet") {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = "Connect to Ynet Network")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Manual connection
        Text(
            text = "Manual Connection",
            style = MaterialTheme.typography.h3
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.ssid,
            onValueChange = { onEvent(WifiTestEvent.UpdateSsid(it)) },
            label = { Text("WiFi SSID") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isConnecting
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = { onEvent(WifiTestEvent.UpdatePassword(it)) },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isConnecting
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onEvent(WifiTestEvent.ConnectToNetwork) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isConnecting && state.ssid.isNotBlank() && state.password.isNotBlank()
        ) {
            if (state.isConnecting && state.currentNetwork != "Ynet") {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = "Connect")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status display
        if (state.statusMessage.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        state.statusMessage.contains("Success", ignoreCase = true) ->
                            MaterialTheme.colorScheme.primaryContainer
                        state.statusMessage.contains("Error", ignoreCase = true) ||
                        state.statusMessage.contains("Failed", ignoreCase = true) ->
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Text(
                    text = state.statusMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
