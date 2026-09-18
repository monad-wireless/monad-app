import Foundation
#if os(iOS)
import ActivityKit
#endif

/// The check-in Live Activity, as one object Kotlin can call.
///
/// A Swift shim rather than Kotlin/Native cinterop, for the same reason `MonadArPoseShim` is one:
/// ActivityKit is a Swift-only framework with generics and `async` properties, and none of
/// `Activity<Attributes>`, `ActivityContent` or `ActivityAuthorizationInfo` is expressible through
/// the Objective-C bridge Kotlin sees. Everything here is `@objc` and takes only strings and
/// numbers, which is the whole surface Kotlin needs.
///
/// ## What it can and cannot do
///
/// - **iOS 16.1 is the floor.** The app's deployment target is 15.3, so every ActivityKit call is
///   behind `#available`. On an older phone `isSupported` is false, the check-in still records,
///   and the participant simply gets no lock-screen indicator.
/// - **The user can switch Live Activities off**, per app, in Settings. `areActivitiesEnabled`
///   reports that and it is not an error — it is a preference, and a check-in that refused to run
///   because of it would be an app arguing with its user.
/// - **The timer is system-ticked.** `startedAt` and `endsAt` go into the attributes and the widget
///   renders `Text(timerInterval:)`. Nothing here redraws once a second, which is what lets the
///   number stay right while the phone is asleep and this process is not running.
/// - **Check out is a `Link`, not a `Button(intent:)`.** An App Intent would act in place without
///   opening the app, and it needs its own target and iOS 17. The link opens the app on
///   `monad://check-out`, which `iOSApp.onOpenURL` turns into a `PresenceCommand`. One extra
///   second for the participant, one fewer moving part.
@objc public class MonadPresenceBridge: NSObject {

    @objc public static let shared = MonadPresenceBridge()

    /// Held so `update` and `stop` address the activity this app started, rather than searching
    /// `Activity.activities` — which is also correct but would quietly act on somebody else's if
    /// the type were ever reused.
    private var activityBox: Any?

    private override init() {
        super.init()
    }

    /// True when this OS can draw the indicator AND the user has not turned Live Activities off.
    @objc public var isSupported: Bool {
        #if os(iOS)
        if #available(iOS 16.1, *) {
            return ActivityAuthorizationInfo().areActivitiesEnabled
        }
        #endif
        return false
    }

    /// Raise the indicator. Returns nil on success, or a short reason the caller may log.
    @objc public func start(place: String, startedAtMillis: Double, endsAtMillis: Double) -> String? {
        #if os(iOS)
        if #available(iOS 16.1, *) {
            guard ActivityAuthorizationInfo().areActivitiesEnabled else {
                return "Live Activities are turned off for this app in Settings"
            }
            // An activity left over from a previous check-in would otherwise sit on the lock
            // screen beside the new one, both claiming to be where this person is.
            endAll()

            let attributes = CheckInActivityAttributes(
                startedAt: Date(timeIntervalSince1970: startedAtMillis / 1000),
                endsAt: Date(timeIntervalSince1970: endsAtMillis / 1000)
            )
            let state = CheckInActivityAttributes.ContentState(place: place)
            do {
                if #available(iOS 16.2, *) {
                    activityBox = try Activity.request(
                        attributes: attributes,
                        content: .init(state: state, staleDate: attributes.endsAt)
                    )
                } else {
                    activityBox = try Activity.request(attributes: attributes, contentState: state)
                }
                return nil
            } catch {
                return error.localizedDescription
            }
        }
        #endif
        return "Live Activities need iOS 16.1 or later"
    }

    /// Redraw it — the participant moved to another card. One activity follows them.
    @objc public func update(place: String) {
        #if os(iOS)
        if #available(iOS 16.1, *) {
            guard let activity = activityBox as? Activity<CheckInActivityAttributes> else { return }
            let state = CheckInActivityAttributes.ContentState(place: place)
            Task {
                if #available(iOS 16.2, *) {
                    await activity.update(.init(state: state, staleDate: activity.attributes.endsAt))
                } else {
                    await activity.update(using: state)
                }
            }
        }
        #endif
    }

    /// Take it down immediately. Safe when nothing is showing.
    @objc public func stop() {
        #if os(iOS)
        if #available(iOS 16.1, *) {
            endAll()
        }
        #endif
        activityBox = nil
    }

    /// Platform posture, for the lab console.
    @objc public func diagnostics() -> [String] {
        #if os(iOS)
        if #available(iOS 16.1, *) {
            if ActivityAuthorizationInfo().areActivitiesEnabled {
                return ["Live Activity: lock screen and Dynamic Island, system-ticked timer"]
            }
            return ["Live Activities are off for this app — no check-in indicator will be shown"]
        }
        #endif
        return ["Live Activities need iOS 16.1 or later — no check-in indicator on this device"]
    }

    @available(iOS 16.1, *)
    private func endAll() {
        #if os(iOS)
        // `.immediate` rather than the default: the participant has just checked out, and an
        // indicator that lingers says they are still being counted.
        for activity in Activity<CheckInActivityAttributes>.activities {
            Task { await activity.end(dismissalPolicy: .immediate) }
        }
        activityBox = nil
        #endif
    }
}
