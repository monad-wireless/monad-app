import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseMessaging

class AppDelegate: NSObject, UIApplicationDelegate {
    /// Whether this build shipped a `GoogleService-Info.plist`.
    ///
    /// Firebase is optional — a bench build has no plist, because the plist carries
    /// per-deployment secrets and is not in the repository. `FirebaseApp.configure()`
    /// is a `fatalError` when the file is absent, so it is asked for rather than assumed,
    /// and push is turned off with it: `Messaging.messaging()` traps the same way.
    /// Crash reporting and push are not needed to build or to run a session.
    private lazy var firebaseAvailable: Bool =
        Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // The ARKit pose shim: Kotlin must never touch an ARFrame (its wrappers retain frames
        // until the Kotlin GC runs, which starves ARKit's capture pool and stops the camera —
        // measured 2026-08-19). The read is compiled here, in MonadArPoseShim.m, and the walk
        // tracker refuses to start if this line is missing.
        ArPoseShim.shared.install(pointer: UnsafeMutableRawPointer(mutating: MonadReadArPoseAddress()))

        // The QR read, from the SAME ARKit frames. Opening a second AVCaptureSession to scan a card
        // is what made the console's old "Scan card" button unusable while tracking, so the decode
        // rides the session that is already running. Missing this line costs card detection only —
        // the manual code entry still works and the walk is unaffected.
        ArBarcodeShim.shared.install(pointer: UnsafeMutableRawPointer(mutating: MonadReadArBarcodeAddress()))

        if firebaseAvailable, FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }

        NotifierManager.shared.initialize(
            configuration: NotificationPlatformConfigurationIos(
                showPushNotification: firebaseAvailable,
                askNotificationPermissionOnStart: firebaseAvailable,
                notificationSoundName: nil
            )
        )
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        guard firebaseAvailable else { return }
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]
    ) async -> UIBackgroundFetchResult {
        NotifierManager.shared.onApplicationDidReceiveRemoteNotification(userInfo: userInfo)
        return UIBackgroundFetchResult.newData
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                // IP-128 — a scanned device label arrives here.
                //
                // Parked rather than routed: Koin is started inside the Compose
                // root's `remember` block, so on a cold start nothing injectable
                // exists yet, and the NavigationManager's SharedFlow (replay = 0)
                // would drop a command emitted before the Navigator's collector
                // is running. The shared UI drains this once it is ready.
                //
                // Covers both entry points — a cold launch delivers the URL here
                // too, so no separate `launchOptions` handling is needed.
                .onOpenURL { url in
                    PendingDeepLink.shared.parkUrl(url: url.absoluteString)
                }
        }
    }
}
