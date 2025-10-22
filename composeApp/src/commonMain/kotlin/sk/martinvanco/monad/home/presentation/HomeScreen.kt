package sk.martinvanco.monad.home.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.home.domain.model.QuestCardDt
import sk.martinvanco.monad.quests.presentation.QuestDetailScreen
import sk.martinvanco.monad.ui.theme.h1
import sk.martinvanco.monad.ui.theme.h3
import sk.martinvanco.monad.wifi_test.presentation.WifiTestScreen

class HomeScreen : Screen {
    // Sample quest data
    private val questsSample = listOf(
        QuestCardDt(
            id = "1",
            name = "Morning Meditation",
            numTasks = 3,
            timeEstimateMin = 15,
            points = 50f,
            questType = "Wellness"
        ),
        QuestCardDt(
            id = "2",
            name = "Code Review Challenge",
            numTasks = 5,
            timeEstimateMin = 45,
            points = 150f,
            questType = "Development"
        ),
        QuestCardDt(
            id = "3",
            name = "Daily Learning Path",
            numTasks = 4,
            timeEstimateMin = 30,
            points = 100f,
            questType = "Education"
        ),
        QuestCardDt(
            id = "4",
            name = "Fitness Sprint",
            numTasks = 6,
            timeEstimateMin = 25,
            points = 75f,
            questType = "Health"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        ),
        QuestCardDt(
            id = "5",
            name = "Creative Writing",
            numTasks = 2,
            timeEstimateMin = 20,
            points = 60f,
            questType = "Creativity"
        )
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<HomeScreenModel>()
        val state by screenModel.state.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, 16.dp, 16.dp, 0.dp)
        ) {
            Text("Home Screen", style = MaterialTheme.typography.h1)
            Spacer(Modifier.height(24.dp))

            // BLE Scanner Section
            Text("BLE Scanner", style = MaterialTheme.typography.h3)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (state.isScanning) {
                            screenModel.onEvent(HomeEvent.StopBleScan)
                        } else {
                            screenModel.onEvent(HomeEvent.StartBleScan)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.isScanning) "Stop Scan" else "Start Scan")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Filter input
            OutlinedTextField(
                value = state.filterText,
                onValueChange = { screenModel.onEvent(HomeEvent.UpdateFilter(it)) },
                label = { Text("Filter by name, address, or UUID") },
                placeholder = { Text("e.g., iPhone, 1800, or device name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            if (state.isScanning) {
                Text(
                    "Scanning... Found ${state.advertisements.size} devices" +
                            if (state.filterText.isNotBlank()) " (${state.filteredAdvertisements.size} filtered)" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val displayedAdvertisements = if (state.filterText.isBlank()) {
                state.advertisements
            } else {
                state.filteredAdvertisements
            }

            if (displayedAdvertisements.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "BLE Advertisements (${displayedAdvertisements.size}):",
                    style = MaterialTheme.typography.h3
                )
                Spacer(Modifier.height(8.dp))
            }

            // Display quest cards and BLE advertisements
            Column (modifier = Modifier.verticalScroll(rememberScrollState())) {
                // BLE Advertisements
                displayedAdvertisements.forEach { ad ->
                    BleAdvertisementCard(ad)
                    Spacer(Modifier.height(8.dp))
                }

                if (displayedAdvertisements.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }

                Text("Available Quests", style = MaterialTheme.typography.h3)
                Spacer(Modifier.height(12.dp))

                questsSample.forEach { quest ->
                    QuestCard(
                        quest = quest,
                        onClick = { navigator.parent?.push(QuestDetailScreen(quest.id)) }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    @Composable
    private fun BleAdvertisementCard(ad: BleAdvertisement) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
            // Device Name
            Text(
                text = ad.name ?: "Unknown Device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // Identifier/Address
            Text(
                text = "Identifier: ${ad.address}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            // RSSI
            Text(
                text = "RSSI: ${ad.rssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )

            // Service UUIDs
            if (!ad.serviceUuids.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Service UUIDs:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                ad.serviceUuids.forEach { uuid ->
                    Text(
                        text = "  • $uuid",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }

            // Manufacturer Data
            if (ad.manufacturerData?.isNotEmpty() == true) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Manufacturer Data:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                ad.manufacturerData.forEach { (companyId, data) ->
                    Text(
                        text = "  Company ID: 0x${companyId.toString(16).uppercase().padStart(4, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        text = "  Data: ${data.joinToString(" ") { byte -> "0x${byte.toString(16).uppercase().padStart(2, '0')}" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }

            // Raw Data
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Raw Packet Data:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = ad.rawData,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            }
        }
    }

    @Composable
    private fun QuestCard(quest: QuestCardDt, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(16.dp)
        ) {
            Text(
                text = quest.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${quest.numTasks} tasks • ${quest.timeEstimateMin} min • ${quest.points.toInt()} pts",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = quest.questType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
