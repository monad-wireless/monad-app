package sk.martinvanco.monad.core.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Parks a deep link between the platform delivering it and the UI being ready
 * to act on it (IP-128).
 *
 * WHY THIS EXISTS AT ALL — it looks like a global, and it is one on purpose.
 * Two facts about this app make the obvious approaches fail on cold start:
 *
 * 1. **Koin is started inside the root composable's `remember` block**
 *    (`App.kt`), so nothing resolvable by DI exists at
 *    `MainActivity.onCreate` / `application(_:didFinishLaunchingWithOptions:)`
 *    time. A link captured there cannot be written into any injected object.
 * 2. **`NavigationManagerImpl` publishes through a `MutableSharedFlow` with
 *    `replay = 0`** whose only collector is a `LaunchedEffect` inside the
 *    `Navigator`. A command emitted before that effect runs is dropped on the
 *    floor — silently, which is the worst possible failure for the *first* thing
 *    a new user ever does with the app.
 *
 * So the link is parked in a plain process-scoped holder that needs no
 * construction and no dependencies, and drained once — by the first composition
 * that is ready to route. [consume] is deliberately take-once: a link that
 * navigated must not fire again on the next recomposition, config change, or
 * return from background, which would yank a participant out of a running quest.
 *
 * WHY THE HOLDER IS OBSERVABLE (2026-09-18). It used to be a `@Volatile` slot
 * that `App()` read exactly once, on first composition. That lost a sticker
 * scanned by anybody who was not already signed in, and it lost it silently:
 * the drain pushed the device screen at once, then the splash's *asynchronous*
 * `navigationManager.replace(LoginScreen())` landed a moment later and replaced
 * the top of the stack — which by then was the device screen, not the splash.
 * The participant reached the login form with no sign that a link had ever
 * arrived, and signing in took them to the home screen.
 *
 * A [StateFlow] fixes the shape rather than the symptom, and is the mechanism
 * [sk.martinvanco.monad.notifications.domain.PendingPushRoute] already uses for
 * the same reason. The link now waits until a screen that is allowed to receive
 * it is on top (see [PreSessionScreen]), so it survives a sign-in, an
 * onboarding run, and a registration, and a link parked while the app is warm
 * still reaches a live collector.
 */
object PendingDeepLink {

    private val _parked = MutableStateFlow<DeepLink?>(null)

    /**
     * The link waiting to be routed, or null.
     *
     * Read this to *decide when* to route. Take it with [consume], never by
     * reading `parked.value` and navigating: only [consume] is take-once.
     */
    val parked: StateFlow<DeepLink?> = _parked.asStateFlow()

    /**
     * Park a link. Called from the platform entry point, possibly before Koin
     * exists. A newer link replaces an unconsumed older one — if a user scans
     * two stickers before the UI catches up, the second is the one they meant.
     */
    fun park(link: DeepLink?) {
        if (link != null) _parked.value = link
    }

    /** Convenience: parse then park. Non-matching URLs are ignored. */
    fun parkUrl(url: String?) = park(DeepLinkParser.parse(url))

    /**
     * Take the parked link, if any, clearing it. Safe to call on every frame.
     *
     * Atomic, so two collectors cannot both route the same sticker.
     */
    fun consume(): DeepLink? = _parked.getAndUpdate { null }

    /** Whether something is waiting. Read-only; does not clear. */
    fun isPending(): Boolean = _parked.value != null

    /** Test seam — no production caller should need this. */
    fun clear() {
        _parked.value = null
    }
}
