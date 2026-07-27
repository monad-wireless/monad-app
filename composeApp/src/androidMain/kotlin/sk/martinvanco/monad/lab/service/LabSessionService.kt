package sk.martinvanco.monad.lab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.aakira.napier.Napier

/**
 * Foreground service that keeps a lab session alive with the screen off.
 *
 * It holds no measurement logic on purpose — the instrument runs in the app process and this
 * service exists only to buy that process the right to keep running. Mixing the two would make the
 * measurement code depend on an Android component and untestable on iOS, which is the platform we
 * lead with.
 *
 * The partial wake lock is not redundant with the foreground service: a foreground service keeps
 * the *process* alive but does not prevent the CPU suspending, and a suspended CPU turns a 100 Hz
 * pacing loop into a sequence of bursts on wake — the exact artefact the illuminator exists to
 * avoid.
 */
class LabSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }

            else -> {
                val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Lab session running"
                startForegroundCompat(reason)
                acquireWakeLock()
            }
        }
        // START_STICKY so the OS restarts us after a low-memory kill; a session that lost its
        // service is better recovered than silently ended.
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundCompat(reason: String) {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MonadCount lab session")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Napier.i("[lab] foreground service started: $reason")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lab sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the phone is acting as a lab instrument"
                setShowBadge(false)
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "sk.martinvanco.monad.lab.START"
        const val ACTION_STOP = "sk.martinvanco.monad.lab.STOP"
        const val EXTRA_REASON = "reason"

        private const val CHANNEL_ID = "monad_lab_session"
        private const val NOTIFICATION_ID = 4210
        private const val WAKE_LOCK_TAG = "monad:lab-session"

        /** Upper bound on a single session, so a crashed app cannot hold the CPU indefinitely. */
        private const val MAX_SESSION_MILLIS = 4L * 60L * 60L * 1000L
    }
}
