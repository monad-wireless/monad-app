package sk.martinvanco.monad.notifications.data

import com.mmk.kmpnotifier.notification.NotifierManager
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.Platform
import sk.martinvanco.monad.lab.domain.HandsetIdentity
import sk.martinvanco.monad.notifications.domain.PushCredentials

/**
 * The production [PushCredentials]: the user table, the installation identity, and kmpNotifier.
 *
 * `getPushNotifier()` throws when the library was never initialised, and `getToken()` returns null
 * on a build without Firebase. Both are "no token", which the registrar treats as nothing to send.
 */
class PushCredentialsAdapter(
    private val users: UserRepository,
    private val handset: HandsetIdentity,
) : PushCredentials {

    override suspend fun authToken(): String? = users.getCurrentUser()?.token

    override suspend fun handsetId(): String? = runCatching { handset.id() }.getOrNull()

    override suspend fun pushToken(): String? =
        runCatching { NotifierManager.getPushNotifier().getToken() }.getOrNull()?.ifBlank { null }

    override fun platform(): String = if (Platform.isIOS) "ios" else "android"
}
