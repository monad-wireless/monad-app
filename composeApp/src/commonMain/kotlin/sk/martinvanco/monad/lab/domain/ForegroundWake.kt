package sk.martinvanco.monad.lab.domain

/**
 * Keeps the screen awake for the length of a foreground-only session.
 *
 * The **opposite** of [BackgroundResidency], and the reason both exist. Residency buys runtime after
 * the app leaves the foreground, which is what a pocketed witness participant needs. A walk needs the
 * other thing: two of its three roles stop working the instant iOS decides the phone is idle.
 *
 *  * **The identity frame.** Backgrounded, iOS moves the service UUID into a proprietary overflow
 *    area a raw HCI scanner cannot parse. The phone still reports itself as advertising.
 *  * **The pose track.** ARKit pauses outright when the app is not foreground.
 *
 * A walk is somebody holding a phone and not touching it, so the auto-lock timer expires after about
 * thirty seconds and both of those happen at once, with no error anywhere. The walk continues to look
 * like it is running: the session is open, the streams simply stop. That is the exact silent failure
 * this instrument's console exists to prevent, and no amount of display can catch it — by the time an
 * operator looks at the screen, looking at the screen has woken it.
 *
 * So this is not a nicety, it is a precondition. Held for the session, released at close, and released
 * in the same `finally` the rest of the teardown lives in — a phone left permanently awake because a
 * session aborted is a flat battery halfway through a lab afternoon.
 */
expect class ForegroundWake() {

    /** True while the screen is being held awake by this object. */
    val isHeld: Boolean

    /**
     * Hold the screen awake. Idempotent — a second hold is not an error and does not nest.
     *
     * Returns a description of what the platform actually did, so the console can say "held" or "not
     * applicable on this platform" rather than implying a guarantee it does not have.
     */
    fun hold(reason: String): String

    fun release()
}
