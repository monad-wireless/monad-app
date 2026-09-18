package sk.martinvanco.monad.core.deeplink

import cafe.adriel.voyager.core.screen.Screen
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.auth.presentation.register.RegisterScreen
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.device.presentation.DeviceScreen
import sk.martinvanco.monad.main.presentation.MainContainerScreen
import sk.martinvanco.monad.marker.presentation.MarkerScreen
import sk.martinvanco.monad.onboarding.presentation.OnboardingScreen

/**
 * The gate that decides when a parked deep link may be routed (IP-128, fixed 2026-09-18).
 *
 * `App()` reads `navigator.lastItem !is PreSessionScreen`. The classification below IS that
 * decision, so it is asserted here rather than left to whoever adds the next screen: before the
 * fix, a sticker scanned while signed out was routed onto the splash and then destroyed by the
 * splash's own `replace(LoginScreen())`, silently.
 */
class PreSessionScreenTest {

    /**
     * Typed as `Screen`, which is how `App()` sees them: `navigator.lastItem` is a `Screen`, so
     * the `is` check is a real runtime test there. Written the same way here, or the compiler
     * folds each assertion to a constant and the test stops guarding anything.
     */
    private fun screen(value: Screen): Screen = value

    @Test
    fun `the four screens shown before a session hold a link`() {
        assertTrue(screen(SplashScreen()) is PreSessionScreen, "the splash replaces the top item")
        assertTrue(screen(OnboardingScreen()) is PreSessionScreen)
        assertTrue(screen(LoginScreen()) is PreSessionScreen)
        assertTrue(screen(RegisterScreen()) is PreSessionScreen)
    }

    @Test
    fun `a signed-in surface accepts a link`() {
        assertFalse(screen(MainContainerScreen()) is PreSessionScreen)
    }

    @Test
    fun `a screen a link routes TO accepts one`() {
        // Otherwise the second sticker of a session would be stranded behind the first.
        assertFalse(screen(DeviceScreen(slug = "monad04", questId = null)) is PreSessionScreen)
        assertFalse(
            screen(
                MarkerScreen(
                    code = "MONAD-FP-07",
                    scannedValue = "https://monad.dubec.dev/m/MONAD-FP-07",
                )
            ) is PreSessionScreen
        )
    }
}

/**
 * The holder itself, now that it is observable rather than a `@Volatile` slot.
 *
 * `PendingDeepLinkTest` (androidUnitTest) already covers park/drain semantics. What is asserted
 * here is the property the gate depends on and the old holder did not have: an unconsumed link
 * stays readable, so a collector that starts LATER still sees it.
 */
class PendingDeepLinkFlowTest {

    @AfterTest
    fun tearDown() = PendingDeepLink.clear()

    @Test
    fun `an unconsumed link is still there for a later collector`() {
        PendingDeepLink.parkUrl("https://monad.dubec.dev/d/monad03")

        // Read twice without consuming: this is what happens while the gate is shut.
        assertEquals(DeepLink.Device(slug = "monad03"), PendingDeepLink.parked.value)
        assertEquals(DeepLink.Device(slug = "monad03"), PendingDeepLink.parked.value)

        assertEquals(DeepLink.Device(slug = "monad03"), PendingDeepLink.consume())
        assertNull(PendingDeepLink.parked.value)
    }

    @Test
    fun `consuming is take-once`() {
        PendingDeepLink.parkUrl("https://monad.dubec.dev/m/MONAD-FP-11")
        assertEquals(DeepLink.Marker(code = "MONAD-FP-11"), PendingDeepLink.consume())
        assertNull(PendingDeepLink.consume())
        assertFalse(PendingDeepLink.isPending())
    }
}
