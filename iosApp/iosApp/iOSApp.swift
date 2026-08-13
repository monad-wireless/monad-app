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
        }
    }
}
