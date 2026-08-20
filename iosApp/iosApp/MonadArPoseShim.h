#ifndef MonadArPoseShim_h
#define MonadArPoseShim_h

// The ARKit pose read the Kotlin tracker must not do itself.
//
// Kotlin/Native retains every ObjC object it touches until its GC runs, so a Kotlin poller
// reading `ARSession.currentFrame` pins multi-megabyte camera capture buffers faster than
// ARKit's shallow pool can stand — ARKit then stops delivering camera images outright (logged
// as "The delegate of ARSession is retaining N ARFrames", measured 73×/min on 2026-08-19, with
// a frozen preview and an empty mesh downstream). Reading via KVC instead crashes: NSInvocation
// cannot box simd types ("struct with unknown contents"). And a cinterop shim is blocked by the
// iOS 26 SDK / Kotlin 2.2.0 mismatch. So the read lives here, compiled by Xcode with the real
// ARKit headers, and Kotlin receives only a function pointer (installed at app start in
// iOSApp.swift) plus plain numbers.
//
// scalars[0] = hasFrame (0/1), [1] = ARFrame.timestamp (CLOCK_UPTIME_RAW seconds),
// [2] = ARTrackingState raw, [3] = ARTrackingStateReason raw.
// matrix    = 16 floats, the camera transform, column-major.
void MonadReadArPose(const void *_Nonnull session,
                     double *_Nonnull scalars,
                     float *_Nonnull matrix);

// The address of MonadReadArPose, for handing across the framework boundary without function
// bitcasts in Swift.
const void *_Nonnull MonadReadArPoseAddress(void);

#endif /* MonadArPoseShim_h */
