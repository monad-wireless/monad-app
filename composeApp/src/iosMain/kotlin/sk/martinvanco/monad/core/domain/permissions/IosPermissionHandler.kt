package sk.martinvanco.monad.core.domain.permissions

class IosPermissionHandler : PermissionHandler {
    override suspend fun checkPermission(permission: Permission): PermissionStatus {
        return PermissionStatus.GRANTED
    }

    override suspend fun requestPermission(permission: Permission): PermissionStatus {
        return PermissionStatus.GRANTED
    }

    override suspend fun requestMultiplePermissions(permissions: List<Permission>): Map<Permission, PermissionStatus> {
        return permissions.associateWith { PermissionStatus.GRANTED }
    }

    override fun openAppSettings() {
        // iOS: UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
    }
}
