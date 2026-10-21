# Changelog

One entry per store build. The heading is the build's `build_id` without the commit part
(`<version>+<versionCode>`, the grammar the session sidecar records, see `ARCHITECTURE.md`).
The version pair lives in `gradle.properties` only. `iosApp`'s `MARKETING_VERSION` and
`CURRENT_PROJECT_VERSION` are copies, and `:composeApp:verifyIosAppVersion` fails the iOS build
when they disagree.

To cut a build:

1. Raise `monad.versionCode` in `gradle.properties` by one. Never reuse a number: App Store Connect
   burns a build number even when it rejects the delivery.
2. Set `CURRENT_PROJECT_VERSION` to the same number at all four sites in
   `iosApp/iosApp.xcodeproj/project.pbxproj` (app and `CheckInWidget`, Debug and Release).
3. Change `monad.version` and `MARKETING_VERSION` only for a new version train. A new train needs
   Beta App Review again (`TESTFLIGHT-BETA.md`, section 5).
4. Run `./gradlew :composeApp:verifyIosAppVersion`.
5. Add the entry below.

## 1.2.0+10 — 2026-10-21

Participant-facing wording and error handling, after the first field runs of the Counting quest
(commit `a7de160`, 2026-10-18).

- Headcount sweep: every refused tap now shows its reason on screen. Before, a refusal by the replay
  or the shape check was discarded, so "Save final total" could do nothing with no message.
  A counter below the last saved total offers the correction it needs instead of a refusal.
- Headcount sweep: the seconds the instrument spends starting show as "starting", not as a failure
  that told people to restart a working run.
- Observe step: the participant chooses how many spots to read. `min_readings` is a suggestion and
  *Done* is available after the first reading. The step screen was rebuilt with short instructions,
  animated transitions and coverage chips that prefill the editable coverage reason.
- Quest start: a refused start reads the server's `reason` (`capability_missing`, `in_progress`,
  `cooldown`, `node_idle`, `window_closed`, `device_inactive`, `not_armed`). Before, every HTTP 409
  read "Already enrolled in this quest".
- Error texts on Home, quest detail and the active quest are shorter and say what to do. A failed
  submission says the data is safe on the phone.
- Home: the quest named "Counting" is listed first. The other quests keep the server's order.
- New `EggLine` component: one line from the public site's footer jokes, tap for another.
- Dwell panel strings (`meta_held_still`, `meta_step`, `meta_panels`) shortened in English and Slovak.
- CI: the build workflow runs on the latest Ubuntu LTS runner (commit `fc3cb39`, 2026-10-17).

Uncommitted in the tree when this entry was written (2026-10-21), and part of this build only if it
is committed before the archive:

- Observe step: the selected room shows on every sweep screen after Start ("Counting: <room>", the
  saved badge and the coverage question) when the quest offers more than one room. A mis-tapped
  room was invisible after Start (2026-10-20, a silent-room count filed as the open room).

## 1.2.0+9 — 2026-09-28

Build number raised with the monorepo restructure (commit `f4a6ca4`). No itemised notes exist for
this build. Read the commit for its contents.

## 1.2.0+8 — 2026-09-20

- iOS: the `CLLocationManager.headingAvailable()` reference is deleted, which removes the two
  location purpose-string warnings from build 7. The iOS handset descriptor no longer reports a
  `heading` sensor fact. `magnetometer` still reports the same physical fact.
- Quest polishing (commit `2b0d8ee`).

## 1.2.0+7 — 2026-09-19

First build accepted by App Store Connect (commit `88766b6`).

- iOS: `NSNearbyInteractionUsageDescription` added (ITMS-90683).
- Build 6 was rejected at processing for three missing purpose strings. Its number is burned.

## 1.2.0+5 — 2026-08-10

- One version for the whole application. Before, `AppConfig.APP_VERSION`, Android and iOS each
  declared a different version. `1.2.0` / `5` is the smallest pair at or above all three on both
  stores (commit `12c9607`).
