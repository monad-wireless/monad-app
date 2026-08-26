package sk.martinvanco.monad.lab.domain

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.concurrent.AtomicReference

/**
 * The bridge to the app-side ARKit pose shim — see `iosApp/MonadArPoseShim.m`.
 *
 * Why a function pointer installed at startup, and not one of the four obvious alternatives, all
 * of which were tried and failed on 2026-08-19, on a real device, in this order:
 *
 * 1. **Kotlin ARFrame wrappers** (`session.currentFrame` from Kotlin): a Kotlin peer retains its
 *    ObjC object until the Kotlin GC collects the wrapper, and a 10 Hz poll pins camera capture
 *    buffers faster than ARKit's shallow pool can stand. ARKit's own log, 73× in one measured
 *    minute: "The delegate of ARSession is retaining N ARFrames. The camera will stop delivering
 *    camera images" — frozen preview, stalling pose stream, permanently empty mesh.
 * 2. **`autoreleasepool` around the poll**: drains the autoreleased reference, not the peer's
 *    retain. Measurably insufficient — the same ARKit warning kept firing.
 * 3. **A cinterop shim**: Kotlin/Native 2.2.0's cinterop fails on *any* def file against the
 *    Xcode 26 SDK ("Could not find platform.UIUtilities"). Door closed until a Kotlin upgrade.
 * 4. **Raw `objc_msgSend` via dlsym with KVC for the matrix**: scalars work, but NSInvocation
 *    cannot box simd types — `valueForKey:@"transform"` throws "struct with unknown contents"
 *    the moment tracking produces a frame. Crashed on device; reproduced on the bench.
 *
 * So the read lives in the iosApp target, compiled by Xcode with the real ARKit headers, under
 * ARC, inside its own autorelease pool. `iOSApp.swift` installs the function pointer before
 * anything tracks; Kotlin calls it with two pinned primitive arrays and receives plain numbers.
 * Nothing on the Kotlin side ever references an ARFrame.
 */
@OptIn(ExperimentalForeignApi::class)
object ArPoseShim {

    /** One pose, as plain numbers. [m] is the column-major camera transform. */
    class Read(
        val hasFrame: Boolean,
        val timestamp: Double,
        val trackingState: Int,
        val trackingReason: Int,
        val m: FloatArray,
    )

    private val installed = AtomicReference<COpaquePointer?>(null)

    /** True once the app registered the shim. The tracker refuses to start without it. */
    val isInstalled: Boolean get() = installed.value != null

    /**
     * Called from `iOSApp.swift` at startup with `MonadArPoseReadAddress()`. Installing twice is
     * harmless; installing something else is on the caller — there is exactly one legitimate
     * argument, and it is spelled out at the call site.
     */
    fun install(pointer: COpaquePointer) {
        installed.value = pointer
    }

    /**
     * Read the current pose from the ARSession behind [sessionPtr], or null when the shim is not
     * installed. `hasFrame == false` means ARKit holds no frame yet — "no new pose", exactly as
     * the poller's freshness check treats it.
     */
    fun read(sessionPtr: COpaquePointer): Read? {
        val fn = installed.value ?: return null
        val reader = fn.reinterpret<
            CFunction<(COpaquePointer?, CPointer<DoubleVar>?, CPointer<FloatVar>?) -> Unit>
        >()
        val scalars = DoubleArray(4)
        val matrix = FloatArray(16)
        scalars.usePinned { s ->
            matrix.usePinned { m ->
                reader(sessionPtr, s.addressOf(0), m.addressOf(0))
            }
        }
        return Read(
            hasFrame = scalars[0] != 0.0,
            timestamp = scalars[1],
            trackingState = scalars[2].toInt(),
            trackingReason = scalars[3].toInt(),
            m = matrix,
        )
    }
}

/**
 * The bridge to the app-side ARKit **barcode** shim — see `iosApp/MonadArPoseShim.m`.
 *
 * Same rationale, same install mechanism and the same pool discipline as [ArPoseShim]; read that
 * doc block first, because every reason it gives for not touching an ARFrame from Kotlin applies
 * here and more so — a Vision request holds the capture buffer while it runs.
 *
 * WHY IT IS A SEPARATE POINTER rather than another field on the pose read. The two are polled at
 * different rates on purpose: the pose at the tracker's own rate, the decode at ~2 Hz, because one
 * Vision request on a 1920×1440 buffer costs tens of milliseconds and the pose stream's phase is
 * something the analysis depends on. Folding the decode into the pose read would put that cost on
 * every pose sample and make the delivered pose rate a measure of Vision's latency.
 *
 * A build that never calls [install] degrades to [isInstalled] false, which the console reads as
 * "this device cannot see cards" and falls back to manual entry. That is also the Android path.
 */
@OptIn(ExperimentalForeignApi::class)
object ArBarcodeShim {

    /**
     * Longest payload accepted, including the terminator.
     *
     * The printed cards carry `https://monad.dubec.dev/m/<slug>`, so 128 is generous by a factor of
     * three. The shim REFUSES a payload that does not fit rather than truncating it: a half-copied
     * URL folds to a different trailing path segment, which is a card code that resolves to the
     * wrong card. Reading nothing is recoverable; reading the wrong card silently is not.
     */
    private const val PAYLOAD_CAPACITY = 128

    private val installed = AtomicReference<COpaquePointer?>(null)

    /** True once the app registered the shim. False means no card detection on this device. */
    val isInstalled: Boolean get() = installed.value != null

    /** Called from `iOSApp.swift` at startup with `MonadReadArBarcodeAddress()`. */
    fun install(pointer: COpaquePointer) {
        installed.value = pointer
    }

    /**
     * The QR payload in the session's current frame, or null.
     *
     * Null covers every uninteresting case identically — shim absent, no frame yet, nothing in
     * view, payload too long — because the caller does the same thing in all of them: keep the
     * previously offered card, or none.
     */
    fun read(sessionPtr: COpaquePointer): String? {
        val fn = installed.value ?: return null
        val reader = fn.reinterpret<
            CFunction<(COpaquePointer?, CPointer<ByteVar>?, Int) -> Int>
        >()
        val payload = ByteArray(PAYLOAD_CAPACITY)
        val found = payload.usePinned { p ->
            reader(sessionPtr, p.addressOf(0), PAYLOAD_CAPACITY)
        }
        if (found == 0) return null
        val end = payload.indexOf(0).let { if (it < 0) payload.size else it }
        if (end == 0) return null
        return payload.decodeToString(0, end)
    }
}
