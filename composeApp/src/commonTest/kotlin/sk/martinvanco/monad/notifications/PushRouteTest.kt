package sk.martinvanco.monad.notifications

import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.notifications.domain.NotificationPermissionState
import sk.martinvanco.monad.notifications.domain.OsNotificationStatus
import sk.martinvanco.monad.notifications.domain.PushRoute
import sk.martinvanco.monad.notifications.domain.resolvePermissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a tapped push does (IP-157), decided from its data payload alone.
 */
class PushRouteTest {

    @Test
    fun aQuestIdOpensTheQuest() {
        assertEquals(
            PushRoute.Quest("q-1", notificationId = "n-9"),
            PushRoute.from(mapOf("quest_id" to "q-1", "notification_id" to "n-9")),
        )
    }

    @Test
    fun aPrintedGrammarLinkGoesWhereTheStickerWould() {
        assertEquals(
            PushRoute.Link(DeepLink.Marker("MONAD-FP-07")),
            PushRoute.from(mapOf("deep_link" to "https://monad.dubec.dev/m/MONAD-FP-07")),
        )
        assertEquals(
            PushRoute.Link(DeepLink.Device("monad04", questId = "abc")),
            PushRoute.from(mapOf("deep_link" to "https://monad.dubec.dev/d/monad04?q=abc")),
        )
    }

    @Test
    fun theQuestWinsWhenBothArePresent() {
        val route = PushRoute.from(
            mapOf("quest_id" to "q-1", "deep_link" to "https://monad.dubec.dev/m/MONAD-FP-07"),
        )
        assertEquals(PushRoute.Quest("q-1"), route)
    }

    @Test
    fun aPlainMessageGoesNowhere() {
        assertNull(PushRoute.from(emptyMap()))
        assertNull(PushRoute.from(mapOf("google.sent_time" to 1L, "gcm.message_id" to "x")))
        assertNull(PushRoute.from(mapOf("quest_id" to "", "deep_link" to " ")))
        // A link off our host is not ours, whatever the desk typed.
        assertNull(PushRoute.from(mapOf("deep_link" to "https://example.org/m/MONAD-FP-07")))
    }

    @Test
    fun nonStringValuesAreReadAsText() {
        // Android intent extras arrive as Any; a numeric quest id must not be dropped for its type.
        assertEquals(PushRoute.Quest("42"), PushRoute.from(mapOf("quest_id" to 42)))
    }

    @Test
    fun thePermissionStateFollowsThePlatformWhenItCanTell() {
        assertEquals(
            NotificationPermissionState.GRANTED,
            resolvePermissionState(OsNotificationStatus(granted = true, determined = true), askedBefore = false),
        )
        assertEquals(
            NotificationPermissionState.DENIED,
            resolvePermissionState(OsNotificationStatus(granted = false, determined = true), askedBefore = false),
        )
        assertEquals(
            NotificationPermissionState.NOT_ASKED,
            resolvePermissionState(OsNotificationStatus(granted = false, determined = false), askedBefore = true),
        )
    }

    @Test
    fun thePermissionStateFallsBackToTheAppsOwnRecordOnAndroid() {
        val undetermined = OsNotificationStatus(granted = false, determined = null)
        assertEquals(NotificationPermissionState.NOT_ASKED, resolvePermissionState(undetermined, askedBefore = false))
        assertEquals(NotificationPermissionState.DENIED, resolvePermissionState(undetermined, askedBefore = true))
    }
}
