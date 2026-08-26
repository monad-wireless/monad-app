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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
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
import sk.martinvanco.monad.home.presentation.model.QuestCardDt
import sk.martinvanco.monad.lab.domain.SessionReport
import sk.martinvanco.monad.lab.presentation.GroundTruthScanScreen
import sk.martinvanco.monad.lab.presentation.LabConsoleScreen
import sk.martinvanco.monad.scan.presentation.ScanShortcutScreen
import sk.martinvanco.monad.lab.presentation.SessionStatusScreen
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
                name = state.userName,
                beaconCount = state.beaconCount,
                isInstrumentRunning = state.isInstrumentRunning,
                unsyncedSessions = state.unsyncedSessions,
                onOpenLabConsole = { navigator.push(LabConsoleScreen()) },
                onOpenCheckIn = { navigator.push(GroundTruthScanScreen()) },
            )

            // The shortcut. Above the quest list on purpose: the commonest thing a
            // participant does is walk past a code, and the slowest route to acting on
            // it was open app, find quest, read quest, press start, press scan. This is
            // one tap and then the camera. `Marker Placement Record.md` ranked it first
            // of the app changes worth making.
            ScanShortcutCard(onClick = { navigator.push(ScanShortcutScreen()) })

            // The participant's question, answered above the fold. A session runs for hours with
            // the app backgrounded; the whole interaction is somebody unlocking their phone and
            // needing "yes · ZONE-B · 4 s ago" without interpreting anything.
            SessionStatusCard(
                state = state,
                onOpenStatus = { navigator.push(SessionStatusScreen()) },
                onOpenCheckIn = { navigator.push(GroundTruthScanScreen()) },
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
                                    navigator.push(QuestDetailScreen(quest.id))
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * "Am I recording? Which zone? How fresh?" — one card, three lines, no jargon.
     *
     * Colour carries the state so it is readable at arm's length, and the headline changes when a
     * stream dies rather than only when the session stops: an instrument that says "recording"
     * while a stream is dead is the exact failure this pass exists to make impossible.
     */
    /**
     * One tap to the camera.
     *
     * Deliberately the largest thing on the screen after the greeting. Every live quest
     * begins with a scan, and a participant holding a phone next to a code should not
     * have to choose a quest first — [ScanShortcutScreen] works out which quest accepts
     * the code and starts it with the scan already counted.
     */
    @Composable
    private fun ScanShortcutCard(onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF5B6ECC),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Scan a code",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Next to a marker or a grey box? Start here — thirty seconds is a " +
                            "whole contribution.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFDBE1FA),
                    )
                }
                Text(text = "\u203A", fontSize = 28.sp, color = Color.White)
            }
        }
    }

    @Composable
    private fun SessionStatusCard(
        state: HomeState,
        onOpenStatus: () -> Unit,
        onOpenCheckIn: () -> Unit,
    ) {
        val background = when {
            !state.isInstrumentRunning -> Color(0xFFF1F5F9)
            state.instrumentIsNominal -> Color(0xFFDCFCE7)
            else -> Color(0xFFFEF3C7)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .clickable(onClick = onOpenStatus)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                state.instrumentHeadline,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
            )
            Text(
                state.zone.current?.let { "You are in ${it.zoneId}" } ?: "You are not checked in",
                fontSize = 14.sp,
                color = Color(0xFF334155),
            )
            Text(
                state.lastEventAgeMillis
                    ?.let { "last event ${SessionReport.formatDuration(it)} ago" }
                    ?: if (state.isInstrumentRunning) "no events yet" else "nothing recording",
                fontSize = 12.sp,
                color = Color(0xFF475569),
            )
            if (state.recoveredSessions > 0) {
                Text(
                    "${state.recoveredSessions} interrupted session(s) recovered",
                    fontSize = 12.sp,
                    color = Color(0xFF92400E),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenCheckIn) {
                    Text(
                        if (state.zone.isCheckedIn) "Move zone / check out" else "Check in",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5B6ECC),
                    )
                }
                TextButton(onClick = onOpenStatus) {
                    Text(
                        "Details",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5B6ECC),
                    )
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
        name: String?,
        beaconCount: Long = 0,
        isInstrumentRunning: Boolean = false,
        unsyncedSessions: Long = 0,
        onOpenLabConsole: () -> Unit = {},
        onOpenCheckIn: () -> Unit = {},
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
                        if (name != null) "Hey $name!" else "Hey!",
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

                // Instrument badge — doubles as the entry point to the lab console. The console is
                // reachable from the first screen on purpose: it is where an operator finds out
                // why a session is not measuring, and that question comes up on a bench, not in a
                // settings menu.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Check in / out. Beside the instrument badge rather than buried in the lab
                    // console, because this one is the *participant's* action: it is the only thing
                    // in the app that records a person rather than a phone, and it has to be
                    // reachable in the two seconds someone spends walking through a door.
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = "Check in or out",
                        tint = Color(0xFF5B6ECC),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .clickable(onClick = onOpenCheckIn)
                            .padding(8.dp)
                            .size(18.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    unsyncedSessions > 0 -> Color(0xFFFEF3C7)
                                    isInstrumentRunning -> Color(0xFFDCFCE7)
                                    else -> Color.White
                                }
                            )
                            .clickable(onClick = onOpenLabConsole)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = "Lab console",
                            tint = if (isInstrumentRunning) Color(0xFF22C55E) else Color(0xFF5B6ECC),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (unsyncedSessions > 0) "$beaconCount · $unsyncedSessions unsynced" else "$beaconCount",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isInstrumentRunning) Color(0xFF166534) else Color(0xFF5B6ECC)
                        )
                    }
                }
            }
        }
    }
}
