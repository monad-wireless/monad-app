package sk.martinvanco.monad.home.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, 16.dp, 16.dp, 0.dp)
        ) {
            Text("Home Screen", style = MaterialTheme.typography.h1)
            Spacer(Modifier.height(24.dp))

            // WiFi Test Button
            Button(
                onClick = { navigator.parent?.push(WifiTestScreen()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("WiFi Connection Test")
            }
            Spacer(Modifier.height(24.dp))

            Text("Available Quests", style = MaterialTheme.typography.h3)
            Spacer(Modifier.height(12.dp))

            // Display quest cards
            Column (modifier = Modifier.verticalScroll(rememberScrollState())) {
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
