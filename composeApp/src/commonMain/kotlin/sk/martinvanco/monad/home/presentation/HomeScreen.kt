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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.home.domain.model.QuestCardDt
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen
import sk.martinvanco.monad.ui.theme.Primary50
import sk.martinvanco.monad.ui.theme.h2

class HomeScreen : Screen {

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
            GreetingsMessage(
                name = "Martin",
                bleRecordCount = state.bleRecordCount,
                isBleCollecting = state.isBleCollecting
            )

            Column(
                modifier = Modifier
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Upcoming Quests",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        color = Color(0xFF000000)
                    )
                    if (!state.isLoadingQuests) {
                        IconButton(
                            onClick = { screenModel.onEvent(HomeEvent.LoadQuests) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF5B6ECC)
                            )
                        }
                    }
                }

                when {
                    state.isLoadingQuests -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF5B6ECC)
                            )
                        }
                    }
                    state.questsError != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.questsError ?: "Unknown error",
                                fontSize = 14.sp,
                                color = Color(0xFF666666),
                                textAlign = TextAlign.Center
                            )
                            TextButton(
                                onClick = { screenModel.onEvent(HomeEvent.LoadQuests) }
                            ) {
                                Text(
                                    text = "Try Again",
                                    color = Color(0xFF5B6ECC),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    state.quests.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No quests available at the moment.\nCheck back later!",
                                fontSize = 14.sp,
                                color = Color(0xFF666666),
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    else -> {
                        Column(
                            Modifier.padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            state.quests.forEach { quest ->
                                QuestCard(quest = quest, onClick = {
                                    navigator.parent?.push(QuestDetailScreen(quest.id))
                                })
                            }
                        }
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
                        if (quest.timeEstimateMin != null) {
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

        }
    }

    @Composable
    private fun GreetingsMessage(
        name: String,
        bleRecordCount: Long = 0,
        isBleCollecting: Boolean = false
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFE2E8FD))
                .padding(18.dp, 16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Hey $name!",
                        style = MaterialTheme.typography.h2,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Ready for your next quest?",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 18.sp
                    )
                }

                // BLE Record Count Badge
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBleCollecting) Color(0xFFDCFCE7) else Color.White)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = "BLE Records",
                        tint = if (isBleCollecting) Color(0xFF22C55E) else Color(0xFF5B6ECC),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "$bleRecordCount",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBleCollecting) Color(0xFF166534) else Color(0xFF5B6ECC)
                    )
                }
            }
        }
    }
}
