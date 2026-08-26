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

// Decode a QR payload from the session's CURRENT ARKit frame.
//
// WHY THIS EXISTS AT ALL. The console used to offer a "Scan card" button backed by a second
// AVCaptureSession, and it had to be disabled whenever the walk was tracking: two capture
// sessions contend for the camera, the OS decides which one loses, and the trajectory develops
// a hole at the exact instant the waypoint is meant to anchor it. So the one control that read
// most naturally was dead precisely when an operator needed it, and every card had to be dialled
// in by hand on a numeric stepper.
//
// There is no contention if nobody opens a second camera. ARKit is already producing frames; this
// hands one of them to Vision and returns what it read. Same session, same pool discipline as the
// pose read above, no new capture device.
//
// Writes a NUL-terminated UTF-8 payload into `out` and returns 1. Returns 0 and leaves `out`
// untouched-but-terminated when there is no frame, no barcode, or the payload does not fit.
// `capacity` includes the terminator.
//
// COST. One Vision request on a 1920×1440 buffer measures in the tens of milliseconds, so this is
// polled at ~2 Hz, never at the pose rate. Orientation is deliberately not passed: QR finder
// patterns are rotation-invariant and ARKit's buffer is camera-native landscape, so supplying a
// wrong orientation would cost detections rather than buy them.
int MonadReadArBarcode(const void *_Nonnull session,
                       char *_Nonnull out,
                       int capacity);

// The address of MonadReadArBarcode, installed the same way as the pose reader.
const void *_Nonnull MonadReadArBarcodeAddress(void);

#endif /* MonadArPoseShim_h */
