package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The "you are checked in" indicator the operating system draws, outside the app.
 *
 * A check-in lasts hours and the phone spends almost all of it locked in a pocket. An in-app card
 * is therefore the one place the state is not visible when it matters: the participant who needs
 * reminding is the one who has walked out of the building without checking out, and they are not
 * looking at a Compose screen. So the state has to leave the app.
 *
 * Each platform draws it with the mechanism it actually has, and they are not equivalent:
 *
 * | | Android | iOS |
 * |---|---|---|
 * | Surface | ongoing foreground-service notification | ActivityKit Live Activity (lock screen + Dynamic Island) |
 * | Timer | `setUsesChronometer`, ticked by the system | `Text(timerInterval:)`, ticked by the system |
 * | Check out | notification action, handled without opening the app | a `Link`, which opens the app and checks out there |
 * | Floor | API 26 for the channel, 33 for the post permission | iOS 16.1, and the user may have Live Activities off |
 *
 * Both timers are **system-ticked from a start instant**, never redrawn by the app once a second.
 * That is the whole reason the snapshot carries instants rather than a formatted string: a phone
 * asleep in a pocket does not get to run a coroutine, and a "12:04" written at post time would
 * still read 12:04 an hour later.
 *
 * ## This is not the measurement
 *
 * Nothing here stamps anything. Every row that reaches storage is stamped once, at the instant of
 * the scan, by [GroundTruthRecorder] — both clocks in the same breath. This class draws a picture
 * of a decision already recorded, and a platform that refuses to draw it costs a reminder and no
 * data. Hence [isSupported] and a [start] that returns a `Result` the caller may ignore.
 */
expect class PresenceIndicator() {

    /** False when this build or this OS version cannot draw the indicator at all. */
    val isSupported: Boolean

    /** Raise the indicator. A failure is not fatal to the check-in; see the class comment. */
    suspend fun start(snapshot: PresenceSnapshot): Result<Unit>

    /** Redraw it — the participant moved to another card. */
    suspend fun update(snapshot: PresenceSnapshot)

    /** Take it down. Safe to call when nothing is showing. */
    suspend fun stop()

    /** Platform posture, for the lab console, so a refusal is legible before a session. */
    fun diagnostics(): List<String>
}

/**
 * What the indicator draws.
 *
 * Instants, not text, so the system ticks the timer while the app is suspended. See
 * [PresenceIndicator].
 */
data class PresenceSnapshot(
    /** The place as a participant reads it — [CheckInPlace.display]. */
    val place: String,
    /** When the check-in began, epoch milliseconds. The timer counts up from here. */
    val startedWallMillis: Long,
    /**
     * When the ceiling closes it, epoch milliseconds.
     *
     * Drawn as the end of the Live Activity's staleness window so iOS retires the widget on its own
     * if the app never gets to. An indicator that outlives its check-in is worse than none: it
     * tells somebody they are being counted when they are not.
     */
    val endsAtWallMillis: Long,
)

/**
 * The way a tap on the OS indicator reaches the app.
 *
 * A single process-wide channel rather than a callback passed down, because the two senders are
 * platform entry points that have no reference to anything — an Android `BroadcastReceiver`
 * constructed by the system, and an iOS URL open handled before the Compose tree exists. Both need
 * to say one word to whoever is listening.
 *
 * `extraBufferCapacity` with `DROP_OLDEST` so an emit from a `BroadcastReceiver` — which is not a
 * coroutine and cannot suspend — never blocks or is lost to a slow collector. Two check-out taps
 * are one check-out, so dropping the older of two is correct.
 */
object PresenceCommands {

    private val _commands = MutableSharedFlow<PresenceCommand>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val commands: SharedFlow<PresenceCommand> = _commands.asSharedFlow()

    /** Callable from a non-coroutine platform entry point. */
    fun send(command: PresenceCommand) {
        _commands.tryEmit(command)
    }
}

enum class PresenceCommand {
    /** "Check out" was tapped on the notification or the Live Activity. */
    CHECK_OUT,
}
