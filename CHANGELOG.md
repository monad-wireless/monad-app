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
6. In Xcode's Distribute App dialog, turn OFF "Manage Version and Build Number". With it on, Xcode
   uploads the next free App Store Connect number while the app keeps recording the number from
   `gradle.properties`, and the two drift apart (see "Build numbers on App Store Connect" below).

### Build numbers on App Store Connect (sorted out 2026-11-23)

Xcode's automatic build-number management renumbered uploads, so App Store Connect numbers and the
`build_id` the app records in every session sidecar disagreed until 1.2.0+18. Mapping, from the
sidecars on S3 and the upload times in App Store Connect:

| App Store Connect | Recorded `build_id` | Note |
|---|---|---|
| 14, 15 (2026-10-28) | `1.2.0+13.g5e543a70.dirty8fbdeab7` | every tester recording up to 2026-11-23 13:43 UTC carries this id; which of 14 or 15 testers ran is not recorded |
| 16 (2026-11-23 13:40) | `1.2.0+14.g124a1298` | the entry 1.2.0+14 below |
| 17 (2026-11-23 14:52) | `1.2.0+15.g124a1298.dirty…` | uncommitted tree, internal testers only; contents not recorded |

From 1.2.0+18 on, the two numbers must be equal.

## 1.2.0+24 — 2026-11-26

- Android: a live step's "Scan" button (the Radio hunt) no longer crashes the app. Build 23 moved
  the camera into `ScannerDialog`, and `CardScanner` bound its moko permission controller inside
  that dialog. On Android a Compose `Dialog` gives its content a wrapper context, not the Activity,
  and moko 0.20.1 `BindEffect` throws `IllegalStateException` on that. The controller is now
  created and bound in `ScanInput`, outside the dialog, and passed to `CardScanner`. iOS was not
  affected. Every Android Radio hunt run on 2026-11-26 ended as "interrupted by a crash".

## 1.2.0+23 — 2026-11-26

- The joke line (`EggLine`, the public site's footer lines) is back, at the foot of the Home quest
  board below the operator takes. Tap it for another line. It is not on the finished screen.
- Every quest camera opens full screen over the step (`ScannerDialog`): live scan, probe and
  scan_qr steps. Up to +22 it opened inline at a fixed 280–300 dp below the step's text, and the
  step had to be scrolled to see the whole camera. A probe step no longer opens the camera by
  itself: the participant taps "Scan the card". A wrong code shows its message inside the camera
  view. While the pose tracker owns the camera, probe and live steps keep their text-only hint
  inline, as before.
- No app change: the "Radio hunt" quest became `audience: public` on 2026-11-26, so every
  participant on build 20 or later sees it on the board.

## 1.2.0+22 — 2026-11-25

Deploy the backend first: `GET /api/auth/me` returns `participant_code`.

- The participant id in a recording is the account's `participant_code` (`p-` and 16 hex digits), the
  same on every phone and after every sign-in. Up to 1.2.0+21 it was the local row id, a login
  counter per phone: two people could both be `1`, and one person got a new number at each sign-in.
  It is in the sidecar, the storage path, the telemetry label and the ground-truth rows. Do not join
  recordings across this boundary by participant id. Use the account or the enrollment.
- A phone that never got a code records `local-<row id>`, so a missing code is visible and the
  recording is not lost. The code is read at sign-in and at each launch.
- The consent page says what the app sends. The old one promised "a random code" and "That is the
  whole list", and neither matched the code.

## 1.2.0+21 — 2026-11-25

This batch needs no backend change of its own, but the build carries 1.2.0+20, which does (see there).
The Study session quest was hidden on the server the same day.

- Home is the quest board. The "Scan to start" card and the "Check in" card are gone: every quest
  step that needs a code opens its own scanner. A quest card shows its time and points, with an icon
  by quest name. A foldable "About this study" card explains the measurement.
- Check in is not reachable. It and a quest share one `IdentityBroadcaster`: an open check-in made a
  quest's broadcast fail with "already broadcasting", and a check-out during a quest took the quest's
  frame off air while its markers still said it was on. `CheckInService`, `CheckInScreen`, the stored
  rows and the widget stay in the code.
- A card scanned with the phone's own camera (`/m/<CODE>`) opens the quest page instead of starting a
  run. If a quest is already open on the phone, it leads back to that quest. Before, a second start
  overwrote the pointer to the open one.
- Onboarding, quest page, marker page, Bluetooth and camera explanations and the finished screen are
  reworded. The terms page (the consent text) is unchanged.
- The joke line (`EggLine`) left Home and the finished screen.
- Removed: `ScanShortcutScreen`.

## 1.2.0+20 — 2026-11-25

Needs the backend of the same day (`live` step type, `POST /api/quest/live/{step}`).

- Live steps: the server drives the screen. The app sends what happened (a scan, an answer, a tap, a
  finished hold), polls about once a second, and draws the widgets it gets back (text, meter, stats,
  scan, number, choice, hold, button, progress). A new mechanic made of these widgets needs no new
  build. First engine: the radio hunt. Capability `quest.live.v1`; an older build is never offered
  such a quest. Code: `quests/domain/live`, `LiveAdapters.kt`, `LiveStep.kt`, `LiveWidgets.kt`,
  `CardScanner.kt`.
- Every live event is also an `annotation` marker (`live:<kind>`) in the recording. A hold writes the
  same waypoint and dwell markers as a probe. A number asked with `record: headcount` writes a
  headcount marker. The existing step types are unchanged.

## 1.2.0+19 — 2026-11-25

- A plain instruction step (start, walk_to, finish) reads two optional config keys: `button` (the
  words on its button, default "Continue") and `elapsed: true` (a count-up since the step began,
  from the step row, so it survives an app restart). Study session revision 2 uses both. Older
  builds ignore them and show "Continue".
- The probe step shows the room in words ("silent reading room") instead of the database key
  ("library-silent").

## 1.2.0+18 — 2026-11-23

Needs the backend deploy of the same day for the "runs" figure (`runs_total`) and for points on
runs an operator completes by hand.

- A refused completion is no longer offered for retry forever (monad-app#2). A 400, 403, 404, 409
  or 422 from `/complete` is final: the same local rows always rebuild the same payload, and a 409
  means the run was already closed by the server or an operator. The run is now closed on the
  phone (step rows and the active quest cleared), the recording stays queued and uploads, and the
  participant sees "Could not be sent" with the status and one OK button. A network error, a 5xx
  or a 401 keeps the old "Not sent yet / Try again" path. The 4xx body is now logged on the phone
  (`CompletionRefusedException`, rule `isFinalRefusal`, test `CompletionRefusalTest`).
- Room and zone sweeps: "What were people doing?" now starts at "Sitting" (`seated`) instead of
  "Not sure" (`unknown`). Applies to Counting and Zone count, which share the observe step. An
  answer the participant did not touch is `seated` in recordings with `build_id` 1.2.0+15 and
  later (the +15 Xcode runs of 2026-11-23 and App Store Connect 17 already have it) and `unknown`
  up to 1.2.0+14: do not compare the `activity` field across that boundary.
- Home: the quest list starts with Counting, Zone count, Fingerprint in that order; the rest keep
  the server's order (`HomeScreenModel.PINNED`).
- Points are shown where a participant looks. Home's header shows the earned total and the runs
  completed (`/api/me/stats` `points_total`, `quests_completed`, re-read whenever Home is shown)
  instead of the points on offer.
  The finished screen shows "+N points" from the completion's `points_earned`, which the app used
  to discard. My account shows a Points card (points, runs started, runs completed) and the quest
  history for everyone; both used to sit behind the "has a measurement" check. "Runs" reads the
  new backend field `runs_total` and is left out against a backend that does not send it.

## 1.2.0+14 — 2026-11-23

Distributed to testers as App Store Connect build 16 (commits `8a65c93`, `124a129`).

- Quest completion after an app restart (monad-app#2). A run resumed after the app was closed or
  crashed could never be submitted: the first step has no recorded start, its start fell back to
  the moment the screen reopened, which was after that step had finished, and the backend refused
  every try with 400 `steps[0].started_at`. A step with no recorded start now starts at its row's
  creation time, which is written once at quest start (`quests/domain/StepTimes.kt`, test
  `StepWindowTest`). In an uninterrupted run only the first step's start moves, and only earlier, by
  the time between tapping Start and the quest screen opening. Every other recorded time is
  unchanged. `mono_ns` and the recording are not affected.

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
