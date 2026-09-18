package sk.martinvanco.monad.lab.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android: an ongoing notification with a system-ticked chronometer and a Check out action.
 *
 * Three choices are deliberate.
 *
 * **`setUsesChronometer` rather than a formatted string.** The system ticks the timer from
 * `whenMillis`, so it stays right while the phone is asleep and the app is not running. A string
 * written at post time would freeze at the minute the check-in started, which on a three-hour dwell
 * is the difference between a reminder and a lie.
 *
 * **`setOngoing` plus a low-importance channel.** Ongoing keeps a swipe from dismissing the one
 * thing that says a person is being counted. Low importance keeps it silent and un-intrusive:
 * nothing about a check-in is urgent, and a channel that buzzes is a channel the participant turns
 * off. On Android 14+ `setOngoing` no longer prevents dismissal for most apps, which is accepted —
 * a dismissed notification costs a reminder, and [CheckInPolicy.MAX_DURATION_MILLIS] still closes
 * the check-in.
 *
 * **The action is a broadcast, not an activity.** Checking out is one tap from the lock screen and
 * does not open the app. That matters because the moment a participant wants to check out is the
 * moment they are leaving, and an app launch they then have to dismiss is what makes people stop
 * bothering.
 */
actual class PresenceIndicator actual constructor() {

    private val context: Context? get() = runCatching { ContextProvider.getContext() }.getOrNull()

    actual val isSupported: Boolean get() = context != null

    actual suspend fun start(snapshot: PresenceSnapshot): Result<Unit> = post(snapshot)

    actual suspend fun update(snapshot: PresenceSnapshot) {
        post(snapshot)
    }

    actual suspend fun stop() {
        val context = context ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
            .onFailure { Napier.w("[check-in] indicator not cancelled: ${it.message}") }
    }

    actual fun diagnostics(): List<String> {
        val context = context ?: return listOf("no application context — indicator unavailable")
        val notes = mutableListOf<String>()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            notes += "notifications are off for this app — no check-in reminder will be shown"
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPostPermission(context)) {
            notes += "POST_NOTIFICATIONS not granted — the indicator is suppressed"
        }
        if (notes.isEmpty()) notes += "ongoing notification, system-ticked timer, check out in one tap"
        return notes
    }

    private fun post(snapshot: PresenceSnapshot): Result<Unit> {
        val context = context
            ?: return Result.failure(IllegalStateException("no application context"))
        if (Build.VERSION.SDK_INT >= 33 && !hasPostPermission(context)) {
            // Not an error the participant needs to see: the check-in is recorded either way.
            Napier.i("[check-in] indicator suppressed, POST_NOTIFICATIONS not granted")
            return Result.failure(SecurityException("POST_NOTIFICATIONS not granted"))
        }

        return runCatching {
            createChannel(context)

            val checkOut = PendingIntent.getBroadcast(
                context,
                REQUEST_CHECK_OUT,
                Intent(context, CheckOutReceiver::class.java).setAction(ACTION_CHECK_OUT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Checked in · ${snapshot.place}")
                .setContentText("Your phone is counting this visit. Tap Check out when you leave.")
                // The system ticks this from `when`, so it survives the process being idle.
                .setWhen(snapshot.startedWallMillis)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                // The ceiling, so the shade agrees with CheckInPolicy about when this ends.
                .setTimeoutAfter((snapshot.endsAtWallMillis - snapshot.startedWallMillis).coerceAtLeast(0L))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Check out", checkOut)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Unit
        }.onFailure { Napier.w("[check-in] indicator not posted: ${it.message}") }
    }

    private fun hasPostPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Check-in", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are checked in somewhere, so you can check out again"
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "monad_check_in"
        const val NOTIFICATION_ID = 4211
        const val REQUEST_CHECK_OUT = 4212
        const val ACTION_CHECK_OUT = "sk.martinvanco.monad.checkin.CHECK_OUT"
    }
}

/**
 * The Check out button.
 *
 * A `BroadcastReceiver` rather than an activity so the tap costs no app launch. It does exactly one
 * thing — say the word into [PresenceCommands] — because a receiver runs on the main thread with a
 * few seconds of life and must not be where a network call or a database write lives. Whoever is
 * collecting does the work.
 *
 * If the process is not running there is nobody collecting, and nothing happens. That is correct
 * rather than a gap: a killed process has no open check-in to close, and the ceiling closes a
 * stranded one on the next launch.
 */
class CheckOutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Napier.i("[check-in] check out tapped on the notification")
        PresenceCommands.send(PresenceCommand.CHECK_OUT)
    }
}
