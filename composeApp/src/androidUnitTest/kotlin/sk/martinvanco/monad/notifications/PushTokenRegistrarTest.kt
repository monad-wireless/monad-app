package sk.martinvanco.monad.notifications

import kotlinx.coroutines.runBlocking
import sk.martinvanco.monad.notifications.domain.PushCredentials
import sk.martinvanco.monad.notifications.domain.PushTokenGateway
import sk.martinvanco.monad.notifications.domain.PushTokenRegistrar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token lifecycle (IP-157): PUT on login, DELETE on logout, never a thrown exception.
 *
 * Under `androidUnitTest` because the registrar is suspend code and `commonTest` is pure by house
 * rule. Fakes, not a network: the rules are about ordering and what is sent, not about HTTP.
 */
class PushTokenRegistrarTest {

    private class FakeGateway : PushTokenGateway {
        val registered = mutableListOf<List<String?>>()
        val unregistered = mutableListOf<Pair<String, String>>()
        var failWith: Exception? = null

        override suspend fun register(authToken: String, pushToken: String, platform: String, handsetId: String?) {
            val failure = failWith
            if (failure != null) throw failure
            registered.add(listOf(authToken, pushToken, platform, handsetId))
        }

        override suspend fun unregister(authToken: String, pushToken: String) {
            val failure = failWith
            if (failure != null) throw failure
            unregistered += authToken to pushToken
        }
    }

    private class FakeCredentials(
        var auth: String? = "jwt-1",
        var handset: String? = "7f3c",
        var push: String? = "fcm-abc",
    ) : PushCredentials {
        override suspend fun authToken() = auth
        override suspend fun handsetId() = handset
        override suspend fun pushToken() = push
        override fun platform() = "android"
    }

    @Test
    fun loginPutsTheTokenWithPlatformAndHandset() = runBlocking {
        val gateway = FakeGateway()
        val registrar = PushTokenRegistrar(gateway, FakeCredentials())

        assertTrue(registrar.registerCurrent())
        val expected: List<List<String?>> = listOf(listOf("jwt-1", "fcm-abc", "android", "7f3c"))
        assertEquals(expected, gateway.registered.toList())
    }

    @Test
    fun aRefreshWhileSignedOutIsDropped() = runBlocking {
        val gateway = FakeGateway()
        val registrar = PushTokenRegistrar(gateway, FakeCredentials(auth = null))

        assertFalse(registrar.onNewToken("fcm-new"))
        assertTrue(gateway.registered.isEmpty())
    }

    @Test
    fun aBuildWithoutFirebaseHasNothingToSend() = runBlocking {
        val gateway = FakeGateway()
        val registrar = PushTokenRegistrar(gateway, FakeCredentials(push = null))

        assertFalse(registrar.registerCurrent())
        assertTrue(gateway.registered.isEmpty())
    }

    @Test
    fun aMissingHandsetIdStillRegisters() = runBlocking {
        val gateway = FakeGateway()
        val registrar = PushTokenRegistrar(gateway, FakeCredentials(handset = null))

        assertTrue(registrar.registerCurrent())
        val expected: List<List<String?>> = listOf(listOf("jwt-1", "fcm-abc", "android", null))
        assertEquals(expected, gateway.registered.toList())
    }

    @Test
    fun logoutDeletesTheCurrentToken() = runBlocking {
        val gateway = FakeGateway()
        val registrar = PushTokenRegistrar(gateway, FakeCredentials())

        assertTrue(registrar.unregisterCurrent())
        val expected: List<Pair<String, String>> = listOf("jwt-1" to "fcm-abc")
        assertEquals(expected, gateway.unregistered.toList())
    }

    @Test
    fun aFailingBackendNeverThrows() = runBlocking {
        val gateway = FakeGateway().apply { failWith = IllegalStateException("no route to host") }
        val registrar = PushTokenRegistrar(gateway, FakeCredentials())

        // Both return false and neither propagates: a dead network must not block a login or a logout.
        assertFalse(registrar.registerCurrent())
        assertFalse(registrar.unregisterCurrent())
    }

    @Test
    fun aFailingCredentialReadNeverThrows() = runBlocking {
        val gateway = FakeGateway()
        val credentials = object : PushCredentials {
            override suspend fun authToken(): String? = throw IllegalStateException("db locked")
            override suspend fun handsetId(): String? = null
            override suspend fun pushToken(): String? = "fcm-abc"
            override fun platform() = "ios"
        }
        val registrar = PushTokenRegistrar(gateway, credentials)

        assertFalse(registrar.registerCurrent())
        assertFalse(registrar.unregisterCurrent())
        assertTrue(gateway.registered.isEmpty())
        assertTrue(gateway.unregistered.isEmpty())
    }
}
