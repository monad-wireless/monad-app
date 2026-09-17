package sk.martinvanco.monad

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration

class MonadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // IP-157 — two channels so a participant can silence callouts in system settings and keep
        // project messages, which is the per-type opt-out Google Play's notification policy wants.
        //
        // kmpNotifier 1.6.1 knows ONE channel (`NotificationChannelData`) and its AndroidNotifier
        // posts every foreground-delivered push on it, so that one is Messages. Quest callouts
        // reach their own channel in the case that matters — the app in the background, where the
        // FCM SDK posts the tray entry itself and honours the message's `android.notification
        // .channel_id`, which the backend sets to CHANNEL_CALLOUTS. A callout received while the
        // app is in the foreground is posted on Messages by the library; that is a limitation of
        // 1.6.1, documented in ARCHITECTURE.md, and the participant is looking at the app then.
        NotifierManager.initialize(
            configuration = NotificationPlatformConfiguration.Android(
                notificationIconResId = R.drawable.ic_launcher_foreground,
                showPushNotification = true,
                notificationChannelData = NotificationPlatformConfiguration.Android.NotificationChannelData(
                    id = CHANNEL_MESSAGES,
                    name = "Messages",
                    description = "News from the lab: sessions, results, changes to the app",
                ),
            )
        )
        createCalloutChannel()
    }

    private fun createCalloutChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_CALLOUTS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLOUTS,
                "Quest callouts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Invitations to run a quest when the lab needs a measurement"
            }
        )
    }

    companion object {
        /** `general` notifications, and the manifest's default for a message without a channel. */
        const val CHANNEL_MESSAGES = "messages"

        /** `quest_callout` notifications. The backend names it in `android.notification.channel_id`. */
        const val CHANNEL_CALLOUTS = "quest_callouts"
    }
}
