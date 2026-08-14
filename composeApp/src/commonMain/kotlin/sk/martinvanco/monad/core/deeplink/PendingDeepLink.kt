package sk.martinvanco.monad.core.deeplink

import kotlin.concurrent.Volatile

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
 */
object PendingDeepLink {

    /**
     * `@Volatile` rather than an atomic reference because the project has no
     * atomicfu dependency and this does not warrant adding one: both the writer
     * (platform entry point) and the reader (composition) run on the main thread,
     * so all this needs to guarantee is visibility, not a compare-and-set.
     */
    @Volatile
    private var parked: DeepLink? = null

    /**
     * Park a link. Called from the platform entry point, possibly before Koin
     * exists. A newer link replaces an unconsumed older one — if a user scans
     * two stickers before the UI catches up, the second is the one they meant.
     */
    fun park(link: DeepLink?) {
        if (link != null) parked = link
    }

    /** Convenience: parse then park. Non-matching URLs are ignored. */
    fun parkUrl(url: String?) = park(DeepLinkParser.parse(url))

    /** Take the parked link, if any, clearing it. Safe to call on every frame. */
    fun consume(): DeepLink? {
        val link = parked
        parked = null
        return link
    }

    /** Whether something is waiting. Read-only; does not clear. */
    fun isPending(): Boolean = parked != null

    /** Test seam — no production caller should need this. */
    fun clear() {
        parked = null
    }
}
