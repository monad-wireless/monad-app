package dev.dubec.monad.quests.domain.live

/**
 * A live step, as the phone understands it: the server decides, the phone draws and reports.
 *
 * The app knows widgets and nothing about hunts. A quest mechanic made of these widgets is written
 * on the server only. The set is closed by the capability `quest.live.v1`; a widget this build does
 * not know is dropped by the adapter rather than failing the screen.
 */
sealed interface LiveWidget {

    /** An input carries an id. It changes when the question changes, which resets the local input. */
    sealed interface Input : LiveWidget {
        val id: String
    }

    data class Text(val text: String, val style: Style) : LiveWidget {
        enum class Style { BODY, HINT, WARNING, SUCCESS }
    }

    /** [value] null means there is nothing to say. It is never drawn as cold. */
    data class Meter(val value: Float?, val label: String, val tone: Tone, val trend: Trend) : LiveWidget {
        enum class Tone { NONE, COLD, COOL, WARM, HOT }
        enum class Trend { NONE, UP, DOWN, FLAT }
    }

    data class Stats(val items: List<Item>) : LiveWidget {
        data class Item(val label: String, val value: String)
    }

    data class Progress(val value: Int, val total: Int, val label: String) : LiveWidget

    data class Scan(override val id: String, val label: String, val hint: String) : Input

    /** [headcount]: the answer is a count of people and also goes into the recording as one. */
    data class Number(
        override val id: String,
        val prompt: String,
        val min: Int,
        val max: Int,
        val initial: Int,
        val headcount: Boolean,
    ) : Input

    data class Choice(override val id: String, val prompt: String, val options: List<Option>) : Input {
        data class Option(val value: String, val label: String)
    }

    data class Hold(
        override val id: String,
        val seconds: Int,
        val label: String,
        val target: Target?,
    ) : Input {
        data class Target(val value: String, val label: String, val room: String, val kind: String)
    }

    data class Button(override val id: String, val label: String, val primary: Boolean) : Input
}

/** One answer of the server. [resultJson] is what the finished step reports as its `step_data`. */
data class LiveScreen(
    val phase: String,
    val title: String,
    val widgets: List<LiveWidget>,
    val pollMillis: Long,
    val done: Boolean,
    val resultJson: String?,
)

/** What the participant did with an input. */
sealed interface LiveAnswer {
    val kind: String

    data class Scanned(val code: String) : LiveAnswer {
        override val kind = "scan"
    }

    data class Counted(val value: Int) : LiveAnswer {
        override val kind = "answer"
    }

    data class Chosen(val value: String) : LiveAnswer {
        override val kind = "answer"
    }

    data object Tapped : LiveAnswer {
        override val kind = "tap"
    }

    data class Held(val seconds: Int) : LiveAnswer {
        override val kind = "hold_done"
    }
}

/** One answer on its way to the server. [id] makes a resend harmless. */
data class LiveEvent(
    val id: String,
    val widgetId: String,
    val answer: LiveAnswer,
    val wallMillis: Long,
    val monotonicNanos: Long,
)

/** What this phone has on air: the service UUID the sensors know it by. */
data class LiveAir(val subject: String?, val broadcasting: Boolean)

data class LiveReply(val screen: LiveScreen, val acked: Set<String>)

/** The server's side of a live step. Throws on a transport failure. */
interface LiveGateway {
    /** @throws LiveRefusedException when a retry of the same call cannot help */
    suspend fun sync(stepCompletionId: String, events: List<LiveEvent>, air: LiveAir): LiveReply
}

/** A final answer: the run is closed, gone, or belongs to somebody else. */
class LiveRefusedException(val status: Int) : Exception("HTTP $status")

/**
 * The recording's side. Every event also lands in the session's own timeline, because the server's
 * log alone would leave the evidence file without the moments it is about.
 */
interface LiveRecorder {
    suspend fun event(stepCompletionId: String, event: LiveEvent)
    suspend fun headcount(prompt: String, count: Int, onAir: Boolean)
    suspend fun holdStarted(target: LiveWidget.Hold.Target)
    suspend fun holdEnded(target: LiveWidget.Hold.Target, seconds: Int)
}
