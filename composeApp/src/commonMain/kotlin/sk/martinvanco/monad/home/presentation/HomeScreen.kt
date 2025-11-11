package sk.martinvanco.monad.home.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import qrscanner.CameraLens
import qrscanner.QrScanner
import sk.martinvanco.monad.ble.domain.BleAdvertisement
import sk.martinvanco.monad.home.domain.model.QuestCardDt
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen
import sk.martinvanco.monad.ui.theme.Primary50
import sk.martinvanco.monad.ui.theme.h1
import sk.martinvanco.monad.ui.theme.h2
import sk.martinvanco.monad.ui.theme.h3
import sk.martinvanco.monad.wifi_test.presentation.WifiTestScreen

class HomeScreen : Screen {
    private val questsSample = listOf(
        QuestCardDt(
            id = "1",
            name = "Morning Meditation to scan XY and more",
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
            name = "Code Review Challenge",
            numTasks = 5,
            timeEstimateMin = 45,
            points = 150f,
            questType = "Development"
        ),
        QuestCardDt(
            id = "4",
            name = "Code Review Challenge",
            numTasks = 5,
            timeEstimateMin = 45,
            points = 150f,
            questType = "Development"
        ),
        QuestCardDt(
            id = "5",
            name = "Code Review Challenge",
            numTasks = 5,
            timeEstimateMin = 45,
            points = 150f,
            questType = "Development"
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GreetingsMessage("Martin")

            Column(
                modifier = Modifier
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Upcomig Quests", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = -1.sp, color = Color(0xFF000000))

                Column (
                    Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    questsSample.forEach { quest ->
                        QuestCard(quest = quest, onClick = {
                            navigator.parent?.push(QuestDetailScreen(quest.id))
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun QuestCard(quest: QuestCardDt, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onClick)
                    .background(Primary50)
                    .padding(20.dp, 20.dp, 20.dp, 20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = quest.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    color = Color(0xFF000000),
                    letterSpacing = (-0.8).sp
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AssignmentTurnedIn,
                                contentDescription = "Tasks",
                                tint = Color(0xFF5B6ECC),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "${quest.numTasks} tasks",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5B6ECC)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Timelapse,
                                contentDescription = "Time",
                                tint = Color(0xFF5B6ECC),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "${quest.timeEstimateMin} min",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5B6ECC)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stars,
                                contentDescription = "Points",
                                tint = Color(0xFFE5A800),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "${quest.points.toInt()} pts",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE5A800)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE2E8FD))
                            .clickable(onClick = onClick)
                            .padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Go to quest",
                            tint = Color(0xFF5B6ECC),
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(45f)
                        )
                    }
                }
            }

            // Tag positioned absolutely at top-right
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-24).dp, y = (-10).dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF0F142F))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = "QR Code",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = quest.questType,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White
                )
            }
        }
    }

    @Composable
    private fun GreetingsMessage(name: String){
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFE2E8FD)).padding(18.dp, 16.dp).fillMaxWidth().height(120.dp)) {
            Text("Hey $name!", style = MaterialTheme.typography.h2, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Ready for your next quest?", style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp)
        }
    }
}
