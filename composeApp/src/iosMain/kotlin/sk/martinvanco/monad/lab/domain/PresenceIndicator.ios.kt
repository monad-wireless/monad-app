package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier

/**
 * iOS: an ActivityKit Live Activity, driven through a shim installed by the app target.
 *
 * Kotlin cannot call ActivityKit. `Activity<Attributes>` is a Swift generic over a protocol with
 * associated types, and none of it survives the Objective-C bridge Kotlin/Native sees — the same
 * wall the ARKit pose read hit, for the same reason ([ArPoseShim]). So the ActivityKit code is one
 * `@objc` class in the app target (`iosApp/MonadPresenceBridge.swift`) and this file only calls it.
 *
 * The call goes through [PresenceShim], which the app installs at launch. Installed rather than
 * imported because the dependency runs the other way: the app target links this framework, so a
 * Kotlin `import` of an app-target class would be a cycle. `iOSApp.swift` hands over four closures
 * the same way it hands over the pose shim's function pointer.
 *
 * **Nothing here is the measurement.** A build with no shim, an iPhone below iOS 16.1, and a user
 * who has turned Live Activities off all produce the same outcome: the check-in is recorded in
 * full, and no indicator appears. That is why [start] returns a `Result` the caller logs rather
 * than an exception it must handle.
 */
actual class PresenceIndicator actual constructor() {

    actual val isSupported: Boolean get() = PresenceShim.isSupported()

    actual suspend fun start(snapshot: PresenceSnapshot): Result<Unit> {
        val refusal = PresenceShim.start(
            snapshot.place,
            snapshot.startedWallMillis.toDouble(),
            snapshot.endsAtWallMillis.toDouble(),
        )
        return if (refusal == null) {
            Result.success(Unit)
        } else {
            Napier.i("[check-in] no lock-screen indicator: $refusal")
            Result.failure(IllegalStateException(refusal))
        }
    }

    actual suspend fun update(snapshot: PresenceSnapshot) {
        PresenceShim.update(snapshot.place)
    }

    actual suspend fun stop() {
        PresenceShim.stop()
    }

    actual fun diagnostics(): List<String> = PresenceShim.diagnostics()
}

/**
 * The seam the app target fills in, mirroring [ArPoseShim].
 *
 * Every function has a default that does nothing and says so, so a build that never installs the
 * shim — a unit test, a simulator run, a half-configured Xcode project — behaves like a device with
 * no Live Activities rather than crashing.
 */
object PresenceShim {

    private var starter: ((String, Double, Double) -> String?)? = null
    private var updater: ((String) -> Unit)? = null
    private var stopper: (() -> Unit)? = null
    private var support: (() -> Boolean)? = null
    private var notes: (() -> List<String>)? = null

    /** True once the app registered the shim. */
    val isInstalled: Boolean get() = starter != null

    /**
     * Called from `iOSApp.swift` at startup. Installing twice is harmless.
     *
     * [start] returns null on success or a short sentence explaining the refusal, which is the one
     * shape that survives the ObjC bridge without an error type.
     */
    fun install(
        start: (place: String, startedAtMillis: Double, endsAtMillis: Double) -> String?,
        update: (place: String) -> Unit,
        stop: () -> Unit,
        supported: () -> Boolean,
        diagnostics: () -> List<String>,
    ) {
        starter = start
        updater = update
        stopper = stop
        support = supported
        notes = diagnostics
        Napier.i("[check-in] presence shim installed")
    }

    internal fun start(place: String, startedAtMillis: Double, endsAtMillis: Double): String? =
        starter?.invoke(place, startedAtMillis, endsAtMillis)
            ?: "this build has no lock-screen indicator"

    internal fun update(place: String) {
        updater?.invoke(place)
    }

    internal fun stop() {
        stopper?.invoke()
    }

    internal fun isSupported(): Boolean = support?.invoke() ?: false

    internal fun diagnostics(): List<String> =
        notes?.invoke() ?: listOf("no presence shim in this build — no check-in indicator")
}
