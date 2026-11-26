package dev.dubec.monad.home.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dubec.monad.home.presentation.model.QuestCardDt
import dev.dubec.monad.lab.domain.SessionReport
import dev.dubec.monad.core.config.AppConfig
import dev.dubec.monad.core.presentation.components.EggLine
import dev.dubec.monad.lab.presentation.LabConsoleScreen
import dev.dubec.monad.lab.presentation.SessionStatusScreen
import dev.dubec.monad.quests.presentation.quest_detail.QuestDetailScreen
import dev.dubec.monad.ui.theme.h2

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
 * | What can I do here? | the quest board |
 * | What is this for? | the foldable card about the study |
 * | Is the instrument healthy? | the instrument card — **operator only** |
 *
 * The scan card and the check-in card left on 2026-11-25. A quest step that needs a code opens its
 * own scanner, so the first duplicated the quests. The second shares one `IdentityBroadcaster` with
 * them, so a check-in could keep a quest's frame off the air. Both started a record from a card
 * somebody happened to stand next to; a record now starts from a quest page only.
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
            // Runs each time Home enters composition, including the return from a finished quest,
            // so the earned total in the header is never the one from before that quest.
            LaunchedEffect(Unit) { screenModel.onEvent(HomeEvent.RefreshPoints) }

            Greeting(
                state = state,
                onOpenLabConsole = { navigator.push(LabConsoleScreen()) },
            )

            QuestBoard(
                state = state,
                onRefresh = { screenModel.onEvent(HomeEvent.LoadQuests) },
                onOpenQuest = { navigator.push(QuestDetailScreen(it)) },
            )

            AboutStudyCard()

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
     * What the study is, for whoever wants to know. Folded, so that it explains without standing
     * between a participant and the quests.
     *
     * Every sentence repeats something the onboarding already says. The consent text lives on the
     * terms page of the onboarding and not here.
     */
    @Composable
    private fun AboutStudyCard() {
        var expanded by remember { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("About this study", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "What we measure, and why your help matters",
                        fontSize = 13.sp,
                        color = Muted,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Muted,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AboutParagraph(
                        heading = "What we measure",
                        text = "The library is full of Wi-Fi signals. A person in their path changes " +
                            "them a little. Small sensor boxes record these changes, and we study " +
                            "whether they are enough to estimate how many people are in a room. The " +
                            "boxes have no camera.",
                    )
                    AboutParagraph(
                        heading = "Why we need your help",
                        text = "An estimate is only useful when it can be checked. In a counting quest " +
                            "you give us the number we compare it with. In a walking quest you stand " +
                            "at a place whose position we know.",
                    )
                    AboutParagraph(
                        heading = "What your phone does",
                        text = "While a quest runs, your phone sends a Bluetooth signal with a changing " +
                            "number. It carries no name and no location. The privacy policy lists " +
                            "everything the app records.",
                    )
                    TextButton(
                        onClick = { uriHandler.openUri("${AppConfig.BASE_URL}/privacy-policy") },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Privacy policy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    }
                }
            }
        }
    }

    @Composable
    private fun AboutParagraph(heading: String, text: String) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(heading, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF334155))
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
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Quests",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.8).sp,
                        color = Ink
                    )
                    Text(
                        "Choose one to see what it involves. Nothing starts until you tap Start.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Muted
                    )
                }
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
                        Text("Try again", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }

                state.quests.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No quest is open at the moment.\nPlease come back later.",
                        fontSize = 14.sp,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }

                else -> {
                    state.participantQuests.forEachIndexed { position, quest ->
                        // Cards rise in one after another on first show. Keyed by id, so a refresh
                        // that changes nothing does not replay the entrance.
                        key(quest.id) {
                            var shown by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                delay(60L * position)
                                shown = true
                            }
                            AnimatedVisibility(
                                visible = shown,
                                enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 4 },
                            ) {
                                QuestCard(
                                    quest = quest,
                                    position = position,
                                    // Only until the first run. After that the participant knows
                                    // the board, and a permanent badge is furniture.
                                    suggested = position == 0 && state.runsCompleted == 0,
                                    onClick = { onOpenQuest(quest.id) },
                                )
                            }
                        }
                    }
                    if (state.isOperator && state.operatorQuests.isNotEmpty()) {
                        OperatorTakes(
                            quests = state.operatorQuests,
                            onOpenQuest = onOpenQuest,
                        )
                    }
                }
            }

            // The website's footer line, at the foot of the board. Tap for another.
            EggLine(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
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
     * card's foot. The cost is time. The step count left the card: forty steps read as a burden and
     * say nothing a duration does not.
     *
     * The card's colour comes from its position in the list. That carries no claim — it is not a
     * difficulty rating and the app does not have one — it exists so a board of five quests looks
     * like five things rather than one thing five times.
     */
    @Composable
    private fun QuestCard(quest: QuestCardDt, position: Int, suggested: Boolean, onClick: () -> Unit) {
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
            if (suggested) {
                Text(
                    text = "A good first quest",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(11.dp)
                ) {
                    Icon(
                        imageVector = questIcon(quest.name),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
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
                        quest.timeEstimateMin?.let { StatChip("about $it min") }
                        if (quest.points > 0f) {
                            StatChip("${formatPoints(quest.points)} points", emphasis = true)
                        }
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open quest",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 3.dp).size(20.dp)
                )
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
                        state.userName?.takeIf { it.isNotBlank() }?.let { "Hello, $it" } ?: "Hello",
                        style = MaterialTheme.typography.h2,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Can Wi-Fi tell how many people are in a room? Your measurements help us find out.",
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
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
                HeroFigure(state.participantQuests.size.toString(), "open quests")
                HeroDivider()
                HeroFigure(state.pointsEarned?.let { formatPoints(it) } ?: "–", "your points")
                HeroDivider()
                HeroFigure(state.runsCompleted?.toString() ?: "–", "runs completed")
                // Beacons heard means something to the person running the lab, not to a student.
                if (state.isOperator) {
                    HeroDivider()
                    HeroFigure(state.beaconCount.toString(), "beacons heard")
                }
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

/**
 * A picture for a quest, by the words in its name. A name this build does not know gets the plain
 * research icon, so a new quest never waits for an app release.
 */
private fun questIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "zone" in n -> Icons.Filled.GridView
        "count" in n -> Icons.Filled.Groups
        "fingerprint" in n -> Icons.Filled.Place
        "hunt" in n -> Icons.Filled.Explore
        "route" in n || "walk" in n || "loop" in n -> Icons.Filled.Route
        else -> Icons.Filled.Science
    }
}

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
