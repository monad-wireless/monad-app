package sk.martinvanco.monad.lab.domain

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
