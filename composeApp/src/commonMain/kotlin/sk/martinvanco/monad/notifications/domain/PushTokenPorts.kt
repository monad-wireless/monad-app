package sk.martinvanco.monad.notifications.domain

/**
 * The backend's push-token endpoints, as the registrar names them.
 *
 * A port rather than the Ktor service so the token lifecycle — PUT on login, DELETE on logout,
 * never a thrown exception either way — is a test against a fake and not a belief about HTTP.
 */
interface PushTokenGateway {
    suspend fun register(authToken: String, pushToken: String, platform: String, handsetId: String?)
    suspend fun unregister(authToken: String, pushToken: String)
}

/**
 * What a registration is made of, read at the moment it is needed.
 *
 * Read late on purpose: the FCM token can be minted after login and the auth token can be gone by
 * the time a refresh arrives, so nothing here is cached by the registrar.
 */
interface PushCredentials {
    /** The bearer token of the signed-in user, or null when nobody is. */
    suspend fun authToken(): String?

    /** The installation UUID from `HandsetIdentity`, or null if the settings store failed. */
    suspend fun handsetId(): String?

    /** The current FCM registration token, or null when Firebase is absent or not ready. */
    suspend fun pushToken(): String?

    /** `ios` or `android`. */
    fun platform(): String
}
