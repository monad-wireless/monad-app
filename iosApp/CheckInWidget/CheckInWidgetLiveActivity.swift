import ActivityKit
import WidgetKit
import SwiftUI

/// The lock-screen and Dynamic Island face of a check-in.
///
/// Three rules govern what is on it.
///
/// **Every timer is `Text(timerInterval:)`.** The system ticks it from the attributes' instants, so
/// it stays right while the phone is asleep and the app is not running. A string formatted here
/// would be redrawn only when the app happens to update the activity, which on a pocketed phone is
/// never.
///
/// **It says where, not how.** "Checked in · Desk 14" is what a participant needs at a glance on a
/// lock screen. Stream states, session ids and delivery fractions belong in the lab console, on an
/// operator's phone, with their hands free.
///
/// **Check out is reachable from here.** The moment somebody wants to check out is the moment they
/// are walking out of the building, and a flow that starts with unlocking the phone and finding the
/// app is a flow people abandon — which leaves an occupancy series claiming they never left.
struct CheckInWidgetLiveActivity: Widget {

    /// Opens the app, which checks out. See `MonadPresenceBridge` for why this is a link rather
    /// than an App Intent.
    private static let checkOutURL = URL(string: "monad://check-out")!

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CheckInActivityAttributes.self) { context in
            lockScreen(context: context)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("Checked in", systemImage: "mappin.and.ellipse")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: context.attributes.startedAt...context.attributes.endsAt,
                         countsDown: false)
                        .font(.system(.title3, design: .rounded).monospacedDigit())
                        .frame(maxWidth: 90, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.place)
                        .font(.headline)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Link(destination: Self.checkOutURL) {
                        Text("Check out")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            } compactLeading: {
                Image(systemName: "mappin.and.ellipse")
            } compactTrailing: {
                Text(timerInterval: context.attributes.startedAt...context.attributes.endsAt,
                     countsDown: false)
                    .monospacedDigit()
                    .frame(maxWidth: 44)
            } minimal: {
                Image(systemName: "mappin.and.ellipse")
            }
            .widgetURL(Self.checkOutURL)
            .keylineTint(Color(red: 0.36, green: 0.43, blue: 0.80))
        }
    }

    @ViewBuilder
    private func lockScreen(context: ActivityViewContext<CheckInActivityAttributes>) -> some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Checked in")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(context.state.place)
                    .font(.headline)
                    .lineLimit(1)
                Text(timerInterval: context.attributes.startedAt...context.attributes.endsAt,
                     countsDown: false)
                    .font(.system(.title2, design: .rounded).monospacedDigit())
            }
            Spacer(minLength: 0)
            Link(destination: Self.checkOutURL) {
                Text("Check out")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(16)
    }
}
