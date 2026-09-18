import WidgetKit
import SwiftUI

/// The extension's contents: one Live Activity and nothing else.
///
/// The template's home-screen widget was deleted rather than left as a placeholder. A widget with
/// no data to show is a tile on somebody's home screen that says "Hello 😀", and this extension
/// exists for one job — telling a participant, on a locked phone, that they are checked in
/// somewhere and how long it has been.
@main
struct CheckInWidgetBundle: WidgetBundle {
    var body: some Widget {
        CheckInWidgetLiveActivity()
    }
}
