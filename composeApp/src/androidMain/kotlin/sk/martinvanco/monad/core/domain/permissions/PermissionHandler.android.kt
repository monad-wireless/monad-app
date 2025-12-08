package sk.martinvanco.monad.core.domain.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPermissionHandler(
    private val context: Context
) : PermissionHandler {

    private val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val REQUEST_CODE_BASE = 1000
        private const val PREF_KEY_REQUESTED_PREFIX = "permission_requested_"
        private val permissionRequests = mutableMapOf<Int, CompletableDeferred<Map<String, Boolean>>>()
        private var requestCodeCounter = REQUEST_CODE_BASE

        fun handlePermissionResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ) {
            permissionRequests[requestCode]?.let { deferred ->
                val results = permissions.mapIndexed { index, permission ->
                    permission to (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED)
                }.toMap()
                deferred.complete(results)
                permissionRequests.remove(requestCode)
            }
        }
    }

    private fun getActivity(): Activity? {
        return when (context) {
            is Activity -> context
            else -> sk.martinvanco.monad.core.util.ContextProvider.getActivity()
        }
    }

    private fun getAndroidPermission(permission: Permission): List<String> {
        return when (permission) {
            Permission.CAMERA -> listOf(Manifest.permission.CAMERA)
            Permission.LOCATION -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            Permission.BLUETOOTH_SCAN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Manifest.permission.BLUETOOTH_SCAN)
                } else {
                    // On older versions, location permission is needed for BLE scanning
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                }
            }
            Permission.BLUETOOTH_CONNECT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    emptyList() // Not needed on older versions
                }
            }
        }
    }

    private fun wasPermissionRequested(permission: Permission): Boolean {
        return prefs.getBoolean(PREF_KEY_REQUESTED_PREFIX + permission.name, false)
    }

    private fun markPermissionAsRequested(permission: Permission) {
        prefs.edit().putBoolean(PREF_KEY_REQUESTED_PREFIX + permission.name, true).apply()
    }

    override suspend fun checkPermission(permission: Permission): PermissionStatus = withContext(Dispatchers.Main) {
        val androidPermissions = getAndroidPermission(permission)
        if (androidPermissions.isEmpty()) {
            return@withContext PermissionStatus.GRANTED
        }

        val allGranted = androidPermissions.all { androidPermission ->
            ContextCompat.checkSelfPermission(context, androidPermission) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            PermissionStatus.GRANTED
        } else {
            // Check if permission was ever requested before
            if (!wasPermissionRequested(permission)) {
                return@withContext PermissionStatus.NOT_DETERMINED
            }

            val activity = getActivity()
            val shouldShowRationale = activity?.let { act ->
                androidPermissions.any { androidPermission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(act, androidPermission)
                }
            } ?: false

            // If shouldShowRationale is true, user denied but can still be asked again
            // If shouldShowRationale is false AND permission was requested before, it's permanently denied
            if (shouldShowRationale) {
                PermissionStatus.DENIED
            } else {
                PermissionStatus.DENIED_PERMANENTLY
            }
        }
    }

    override suspend fun requestPermission(permission: Permission): PermissionStatus = withContext(Dispatchers.Main) {
        val currentStatus = checkPermission(permission)
        if (currentStatus == PermissionStatus.GRANTED) {
            return@withContext PermissionStatus.GRANTED
        }

        val androidPermissions = getAndroidPermission(permission)
        if (androidPermissions.isEmpty()) {
            return@withContext PermissionStatus.GRANTED
        }

        val activity = getActivity()
        if (activity == null) {
            // If we can't get the activity, return denied
            return@withContext PermissionStatus.DENIED
        }

        // Mark permission as requested before showing dialog
        markPermissionAsRequested(permission)

        val requestCode = requestCodeCounter++
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        permissionRequests[requestCode] = deferred

        ActivityCompat.requestPermissions(
            activity,
            androidPermissions.toTypedArray(),
            requestCode
        )

        val results = deferred.await()
        val allGranted = results.values.all { it }

        if (allGranted) {
            PermissionStatus.GRANTED
        } else {
            val shouldShowRationale = androidPermissions.any { androidPermission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission)
            }

            // If shouldShowRationale is true, user denied but can still be asked again
            // If shouldShowRationale is false, user selected "Don't ask again"
            if (shouldShowRationale) {
                PermissionStatus.DENIED
            } else {
                PermissionStatus.DENIED_PERMANENTLY
            }
        }
    }

    override suspend fun requestMultiplePermissions(permissions: List<Permission>): Map<Permission, PermissionStatus> = withContext(Dispatchers.Main) {
        val results = mutableMapOf<Permission, PermissionStatus>()

        for (permission in permissions) {
            results[permission] = requestPermission(permission)
        }

        results
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
