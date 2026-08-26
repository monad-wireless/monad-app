#import "MonadArPoseShim.h"
#import <ARKit/ARKit.h>
#import <Vision/Vision.h>
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

int MonadReadArBarcode(const void *_Nonnull sessionPtr,
                       char *_Nonnull out,
                       int capacity) {
    if (capacity <= 0) {
        return 0;
    }
    out[0] = '\0';
    // Same pool discipline as the pose read: the frame and its capture buffer are released before
    // this function returns, so the caller can poll without ever pinning one. Vision's request
    // handler holds the buffer only for the duration of the synchronous `performRequests:` below.
    @autoreleasepool {
        ARSession *session = (__bridge ARSession *)sessionPtr;
        ARFrame *frame = session.currentFrame;
        if (frame == nil) {
            return 0;
        }
        CVPixelBufferRef pixels = frame.capturedImage;
        if (pixels == NULL) {
            return 0;
        }

        VNDetectBarcodesRequest *request = [[VNDetectBarcodesRequest alloc] init];
        // QR only. The lab prints nothing else, and every extra symbology is detector work spent
        // on a format that cannot appear — this runs twice a second for the length of a walk.
        request.symbologies = @[ VNBarcodeSymbologyQR ];

        VNImageRequestHandler *handler =
            [[VNImageRequestHandler alloc] initWithCVPixelBuffer:pixels options:@{}];

        NSError *error = nil;
        if (![handler performRequests:@[ request ] error:&error] || error != nil) {
            return 0;
        }

        // FIRST RESULT, NOT THE LARGEST. Vision orders observations by confidence, and an operator
        // standing at a card fills the frame with it. Picking by bounding-box area instead would
        // prefer a poster across the room over the card in front of the phone.
        for (VNBarcodeObservation *observation in request.results) {
            NSString *payload = observation.payloadStringValue;
            if (payload.length == 0) {
                continue;
            }
            const char *utf8 = payload.UTF8String;
            if (utf8 == NULL) {
                continue;
            }
            // Refuse rather than truncate. A half-copied payload is a card code that resolves to
            // the wrong card, which is worse than reading nothing at all.
            if (strlen(utf8) >= (size_t)capacity) {
                continue;
            }
            strlcpy(out, utf8, (size_t)capacity);
            return 1;
        }
    }
    return 0;
}

const void *_Nonnull MonadReadArBarcodeAddress(void) {
    return (const void *)&MonadReadArBarcode;
}
