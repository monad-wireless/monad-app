package sk.martinvanco.monad.core.domain.permissions

enum class Permission {
    BLUETOOTH_SCAN,
    BLUETOOTH_CONNECT,
    LOCATION,
    CAMERA
}

enum class PermissionStatus {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY,
    NOT_DETERMINED
}

interface PermissionHandler {
    suspend fun checkPermission(permission: Permission): PermissionStatus
    suspend fun requestPermission(permission: Permission): PermissionStatus
    suspend fun requestMultiplePermissions(permissions: List<Permission>): Map<Permission, PermissionStatus>
    fun openAppSettings()
}
