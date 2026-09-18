package sk.martinvanco.monad.home.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.home.presentation.model.QuestCardDt
import sk.martinvanco.monad.lab.domain.SessionReport
import sk.martinvanco.monad.lab.presentation.CheckInScreen
import sk.martinvanco.monad.lab.presentation.LabConsoleScreen
import sk.martinvanco.monad.lab.presentation.SessionStatusScreen
import sk.martinvanco.monad.lab.presentation.formatElapsed
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen
import sk.martinvanco.monad.scan.presentation.ScanShortcutScreen
import sk.martinvanco.monad.ui.theme.h2

/**
 * The board a student opens the app on.
 *
 * ## What it used to be, and why that was confusing
 *
 * Five blocks competed above the quest list, three of them about an instrument a participant is not
 * holding. The greeting carried a "console" badge, a check-in QR icon and three figures; below it a
 * "Scan a code" card offered the camera; below that a status card said "Not recording · You are not
 * checked in" — two unrelated facts in one sentence — with its own Check in button, which was the
 * third route to a camera on one screen. Two of the three figures ("quests open", "points on offer")
 * were then repeated verbatim in the Quests header eighty pixels lower.
 *
 * ## What it is now: one card per question
 *
 * | Question | Block |
 * |---|---|
 * | Am I being counted, and for how long? | the check-in card |
 * | I am standing next to a code | the scan card |
 * | What can I do here? | the quest board |
 * | Is the instrument healthy? | the instrument card — **operator only** |
 *
 * The three operator surfaces — the console badge, the instrument card and the operator takes — are
 * behind [HomeState.isOperator]. A student's home screen now contains nothing they cannot act on.
 * The duplicate figures were removed from the Quests header rather than from the greeting, because
 * the greeting is where somebody looks first.
 */
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Greeting(
                state = state,
                onOpenLabConsole = { navigator.push(LabConsoleScreen()) },
            )

            // The participant's own question, above everything else: am I being counted right now?
            // It is also the only thing in the app that records a PERSON rather than a phone, so it
            // outranks the quest board.
            CheckInCard(
                state = state,
                onOpenCheckIn = { navigator.push(CheckInScreen()) },
            )

            // One tap to the camera for a QUEST. Distinct from the card above: that one counts a
            // visit, this one starts a run. They were previously two buttons that both said "scan"
            // and did different things without saying so.
            ScanShortcutCard(onClick = { navigator.push(ScanShortcutScreen()) })

            QuestBoard(
                state = state,
                onRefresh = { screenModel.onEvent(HomeEvent.LoadQuests) },
                onOpenQuest = { navigator.push(QuestDetailScreen(it)) },
            )

            // Operator only. Per-stream liveness is unreadable and unactionable for a participant,
            // and for months it told every student "Not recording" about a session they were never
            // going to run.
            if (state.isOperator) {
                InstrumentCard(
                    state = state,
                    onOpenStatus = { navigator.push(SessionStatusScreen()) },
                )
            }
        }
    }

    /**
     * Checked in: where, how long, and the way out. Not checked in: what to do about it.
     *
     * The elapsed time is the whole point. It is the one number that tells a participant the phone
     * is still counting them, it is what the lock-screen indicator shows, and it is what makes
     * "check out when you leave" a thing somebody remembers to do.
     */
    @Composable
    private fun CheckInCard(state: HomeState, onOpenCheckIn: () -> Unit) {
        val active = state.activeCheckIn
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (active != null) Color(0xFFDCFCE7) else Color(0xFFF1F5F9))
                .clickable(onClick = onOpenCheckIn)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (active != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiveDot(running = true, nominal = true)
                    Text(
                        "Checked in at ${active.place.display}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatElapsed(active.elapsedMillis(state.nowMillis)),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF166534),
                )
                Text(
                    "Tap to check out when you leave.",
                    fontSize = 12.sp,
                    color = Color(0xFF334155),
                )
            } else {
                Text(
                    "Not checked in",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    "Scan any card where you are sitting and the phone counts how long you stay. " +
                        "This is the one thing here that counts people rather than phones.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF334155),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Check in →",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Accent,
                )
            }
        }
    }

    @Composable
    private fun QuestBoard(
        state: HomeState,
        onRefresh: () -> Unit,
        onOpenQuest: (String) -> Unit,
    ) {
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The count and the points used to be repeated here, verbatim, from the greeting
                // eighty pixels above. One statement of a number is enough.
                Text(
                    "Quests",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    color = Ink
                )
                if (!state.isLoadingQuests) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = Accent
                        )
                    }
                }
            }

            when {
                state.isLoadingQuests -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Accent)
                }

                state.questsError != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = state.questsError ?: "Unknown error",
                        fontSize = 14.sp,
                        color = Muted,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onRefresh) {
                        Text("Try Again", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }

                state.quests.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No quests available at the moment.\nCheck back later!",
                        fontSize = 14.sp,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }

                else -> {
                    state.participantQuests.forEachIndexed { position, quest ->
                        QuestCard(
                            quest = quest,
                            position = position,
                            onClick = { onOpenQuest(quest.id) },
                        )
                    }
                    if (state.isOperator && state.operatorQuests.isNotEmpty()) {
                        OperatorTakes(
                            quests = state.operatorQuests,
                            onOpenQuest = onOpenQuest,
                        )
                    }
                }
            }
        }
    }

    /**
     * Operator takes, folded away.
     *
     * Collapsed by default and never counted in the board's headline figures. The operator opening
     * the app is usually about to check something rather than about to walk a take, and an
     * unfolded list of them at the bottom of a student board is the state that caused the
     * complaint.
     */
    @Composable
    private fun OperatorTakes(
        quests: List<QuestCardDt>,
        onOpenQuest: (String) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Operator takes",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    Text(
                        "${quests.size} hidden from participants",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Color(0xFF64748B)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    quests.forEach { quest ->
                        OperatorTakeRow(quest = quest, onClick = { onOpenQuest(quest.id) })
                    }
                }
            }
        }
    }

    /** Deliberately plainer than a quest card. It is a tool, not an offer. */
    @Composable
    private fun OperatorTakeRow(quest: QuestCardDt, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = quest.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${quest.numTasks} steps")
                        quest.timeEstimateMin?.let { append(" · $it min") }
                    },
                    fontSize = 12.sp,
                    color = Muted
                )
            }
            Text("›", fontSize = 22.sp, color = Color(0xFF94A3B8))
        }
    }

    /**
     * One tap to the camera, for a quest.
     *
     * [ScanShortcutScreen] works out which quest accepts the code and starts it with the scan
     * already counted. Distinct from the check-in card above, and the copy now says which is which:
     * two buttons that both said "scan a code" were most of why this screen was hard to read.
     */
    @Composable
    private fun ScanShortcutCard(onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScanBrush)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Start a quest by scanning",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Point at a card or a grey box and the right quest starts itself. " +
                            "The shortest takes thirty seconds.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFDBE1FA),
                    )
                }
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }

    /**
     * The instrument, for an operator. "Is it recording, and is every stream alive?"
     *
     * Colour carries the state so it is readable at arm's length, and the headline changes when a
     * stream dies rather than only when the session stops: an instrument that says "recording"
     * while a stream is dead is the exact failure this pass exists to make impossible.
     */
    @Composable
    private fun InstrumentCard(state: HomeState, onOpenStatus: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveDot(running = state.isInstrumentRunning, nominal = state.instrumentIsNominal)
                Text(
                    state.instrumentHeadline,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
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
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenStatus) {
                    Text("Details", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                }
            }
        }
    }

    /**
     * One quest, as an offer.
     *
     * The figures are a chip row directly under the name: a participant deciding whether to walk
     * this reads the name and the cost in one glance instead of tracking down a column at the
     * card's foot.
     *
     * The card's colour comes from its position in the list. That carries no claim — it is not a
     * difficulty rating and the app does not have one — it exists so a board of five quests looks
     * like five things rather than one thing five times.
     */
    @Composable
    private fun QuestCard(quest: QuestCardDt, position: Int, onClick: () -> Unit) {
        val brush = QuestBrushes[position % QuestBrushes.size]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(brush)
                .clickable(onClick = onClick)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = quest.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        color = Color.White,
                        letterSpacing = (-0.6).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatChip("${quest.numTasks} steps")
                        quest.timeEstimateMin?.let { StatChip("$it min") }
                        StatChip("${formatPoints(quest.points)} pts", emphasis = true)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open quest",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).rotate(-45f)
                    )
                }
            }

            if (quest.description.isNotBlank()) {
                Text(
                    text = quest.description,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    /** One figure from a quest card's totals row. */
    @Composable
    private fun StatChip(text: String, emphasis: Boolean = false) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasis) Color(0xFF2A1A00) else Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (emphasis) Color(0xFFFFC24D) else Color.White.copy(alpha = 0.2f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }

    /** Green and breathing while something is live, still otherwise. */
    @Composable
    private fun LiveDot(running: Boolean, nominal: Boolean) {
        val alpha = if (running && nominal) {
            rememberInfiniteTransition(label = "live").animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "liveAlpha",
            ).value
        } else {
            1f
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !running -> Color(0xFF94A3B8)
                        nominal -> Color(0xFF22C55E).copy(alpha = alpha)
                        else -> Color(0xFFF59E0B)
                    }
                )
        )
    }

    /**
     * The greeting, and the three figures under it.
     *
     * The console badge beside the greeting is the operator's entry to the lab console. It is on
     * the first screen on purpose — it is where an operator finds out why a session is not
     * measuring, and that question comes up on a bench rather than in a settings menu — but it is
     * shown only to an operator. A student has no session to diagnose and no panel they can act on.
     *
     * The check-in QR that used to sit beside it is gone. Check-in is now a card of its own
     * directly below, which is both larger and says what it does.
     */
    @Composable
    private fun Greeting(
        state: HomeState,
        onOpenLabConsole: () -> Unit,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(HeroBrush)
                .padding(20.dp, 18.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.userName != null) "Hey ${state.userName}!" else "Hey!",
                        style = MaterialTheme.typography.h2,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Check in where you sit, or walk a quest.",
                        fontSize = 15.sp,
                        color = Color(0xFFC9D2F7)
                    )
                }

                if (state.isOperator) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                when {
                                    state.unsyncedSessions > 0 -> Color(0xFFFEF3C7)
                                    state.isInstrumentRunning -> Color(0xFFDCFCE7)
                                    else -> Color.White.copy(alpha = 0.16f)
                                }
                            )
                            .clickable(onClick = onOpenLabConsole)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = "Lab console",
                            tint = when {
                                state.unsyncedSessions > 0 -> Color(0xFF92400E)
                                state.isInstrumentRunning -> Color(0xFF166534)
                                else -> Color.White
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (state.unsyncedSessions > 0) {
                                "${state.unsyncedSessions} unsynced"
                            } else {
                                "console"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                state.unsyncedSessions > 0 -> Color(0xFF92400E)
                                state.isInstrumentRunning -> Color(0xFF166534)
                                else -> Color.White
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroFigure(state.participantQuests.size.toString(), "quests open")
                HeroDivider()
                HeroFigure(formatPoints(state.pointsOnOffer), "points on offer")
                HeroDivider()
                HeroFigure(state.beaconCount.toString(), "beacons heard")
            }
        }
    }

    @Composable
    private fun HeroFigure(value: String, label: String) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(label, fontSize = 11.sp, color = Color(0xFF9FAEE8))
        }
    }

    @Composable
    private fun HeroDivider() {
        Box(Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.14f)))
    }
}

private fun formatPoints(points: Float): String =
    if (points % 1f == 0f) points.toInt().toString() else points.toString()

private val Accent = Color(0xFF5B6ECC)
private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)

private val HeroBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF2A3574), Color(0xFF141A3A)),
    start = Offset.Zero,
    end = Offset(900f, 600f),
)

private val ScanBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF6B7DE0), Color(0xFF4453B4)),
    start = Offset.Zero,
    end = Offset(900f, 300f),
)

/**
 * The quest-card palette, applied by position in the list.
 *
 * By POSITION rather than by points or step count, and that is the honest choice: a colour keyed to
 * a quest's points would look like a difficulty rating the app has never measured. Position carries
 * no claim at all — it only stops five cards reading as one.
 */
private val QuestBrushes = listOf(
    Brush.linearGradient(
        colors = listOf(Color(0xFF4F60C9), Color(0xFF39479E)),
        start = Offset.Zero,
        end = Offset(800f, 500f),
    ),
    Brush.linearGradient(
        colors = listOf(Color(0xFF2E8C7E), Color(0xFF1F6659)),
        start = Offset.Zero,
        end = Offset(800f, 500f),
    ),
    Brush.linearGradient(
        colors = listOf(Color(0xFF7A5AC4), Color(0xFF553B95)),
        start = Offset.Zero,
        end = Offset(800f, 500f),
    ),
    Brush.linearGradient(
        colors = listOf(Color(0xFFC06A3A), Color(0xFF944C24)),
        start = Offset.Zero,
        end = Offset(800f, 500f),
    ),
)
