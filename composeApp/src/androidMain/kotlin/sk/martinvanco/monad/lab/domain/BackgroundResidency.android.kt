package sk.martinvanco.monad.lab.domain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.core.util.ContextProvider
import sk.martinvanco.monad.lab.service.LabSessionService

/**
 * Android residency: a foreground service plus a partial wake lock, and a prompt for the
 * battery-optimisation exemption.
 *
 * All three are needed. The foreground service stops the process being frozen; the wake lock keeps
 * the CPU available for the emitter's pacing loop while the screen is off; and without the
 * exemption several OEM ROMs will still throttle or kill the process, which is precisely the class
 * of failure that only shows up during a field session and never on a desk.
 */
actual class BackgroundResidency actual constructor() {

    private val context: Context get() = ContextProvider.getContext()

    private val _isActive = MutableStateFlow(false)
    actual val isActive: Flow<Boolean> = _isActive.asStateFlow()

    actual suspend fun acquire(reason: String): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // The service is still allowed to run, but its notification is suppressed and the user
            // gets no indication the instrument is live. Report rather than hide it.
            Napier.w("[lab] POST_NOTIFICATIONS not granted — the session notification will be hidden")
        }

        val intent = Intent(context, LabSessionService::class.java).apply {
            action = LabSessionService.ACTION_START
            putExtra(LabSessionService.EXTRA_REASON, reason)
        }
        ContextCompat.startForegroundService(context, intent)
        _isActive.value = true
    }

    actual fun release() {
        runCatching {
            context.startService(
                Intent(context, LabSessionService::class.java).apply {
                    action = LabSessionService.ACTION_STOP
                }
            )
        }
        _isActive.value = false
    }

    actual fun diagnostics(): List<ResidencyCheck> {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        return listOf(
            ResidencyCheck(
                name = "foreground_service",
                satisfied = _isActive.value,
                detail = if (_isActive.value) "running" else "not started",
            ),
            ResidencyCheck(
                name = "battery_optimisation_exempt",
                satisfied = exempt,
                detail = if (exempt) "exempt" else "NOT exempt — OEM ROMs may freeze the session",
            ),
            ResidencyCheck(
                name = "background_location",
                satisfied = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                detail = "needed for scanning with the screen off on API 29+",
            ),
            ResidencyCheck(
                name = "notifications",
                satisfied = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                detail = "session notification visibility",
            ),
        )
    }

    actual suspend fun requestPrerequisites(): Result<Unit> = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isIgnoringBatteryOptimizations(context.packageName) == false) {
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is policy-restricted on Play Store
            // builds; the settings screen always works and this is a sideloaded research app.
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        Unit
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Deep link to this app's settings page, offered by the console when a check fails. */
    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
