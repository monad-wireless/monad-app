#import "MonadArPoseShim.h"
#import <ARKit/ARKit.h>
#import <string.h>

void MonadReadArPose(const void *_Nonnull sessionPtr,
                     double *_Nonnull scalars,
                     float *_Nonnull matrix) {
    scalars[0] = 0.0;
    scalars[1] = 0.0;
    scalars[2] = 0.0;
    scalars[3] = 0.0;
    memset(matrix, 0, 16 * sizeof(float));
    // The pool is the point: the frame is fully released before this function returns, so the
    // caller can poll at any rate without ever holding a capture buffer.
    @autoreleasepool {
        ARSession *session = (__bridge ARSession *)sessionPtr;
        ARFrame *frame = session.currentFrame;
        if (frame == nil) {
            return;
        }
        ARCamera *camera = frame.camera;
        scalars[0] = 1.0;
        scalars[1] = frame.timestamp;
        scalars[2] = (double)camera.trackingState;
        scalars[3] = (double)camera.trackingStateReason;
        simd_float4x4 transform = camera.transform;
        memcpy(matrix, &transform, 16 * sizeof(float));
    }
}

const void *_Nonnull MonadReadArPoseAddress(void) {
    return (const void *)&MonadReadArPose;
}
