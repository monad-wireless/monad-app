import ActivityKit
import Foundation

/// The shape of the check-in Live Activity, shared by the app and the widget extension.
///
/// ActivityKit matches a running Activity to its widget by this type's name, so the two targets
/// must agree about it exactly. It is one file compiled into both targets rather than two
/// declarations that happen to match: a field added on one side only would not fail to build, it
/// would fail to *render*, silently, on somebody's lock screen.
///
/// ## What is fixed and what moves
///
/// `place` moves, because a participant who scans a second card has moved rather than started
/// again — one Activity follows them. `startedAt` and `endsAt` are fixed for the life of one
/// check-in and are what the system ticks the timer from, which is the whole reason they are
/// instants and not a formatted string: a phone asleep in a pocket runs no code, and a "00:12"
/// written at post time would still read 00:12 an hour later.
struct CheckInActivityAttributes: ActivityAttributes {

    public struct ContentState: Codable, Hashable {
        /// The place as a participant reads it: a quest's label for the card, or the card's code.
        var place: String
    }

    /// When the check-in began. The lock-screen timer counts up from here.
    var startedAt: Date

    /// When `CheckInPolicy.MAX_DURATION_MILLIS` closes it, so the system can retire the widget
    /// even if the app never gets the chance to.
    var endsAt: Date
}
