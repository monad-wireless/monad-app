# monad-app

The MonadCount **participant instrument**: a Kotlin Multiplatform (Compose) app that turns a phone
into part of the measurement apparatus for the lab's wireless-sensing experiments.

A phone here plays three roles at once — **illuminator** (emits a paced UDP stream from a station
associated to an experiment AP, giving the fleet's CSI receivers a uniform sounding source),
**witness** (observes surveyed iBeacon anchors and reports zone transitions), and **subject** (a
body in the room). It never captures CSI: no mobile OS exposes it.

Two constraints shape the whole design — **iOS is the first-class target**, and **the app is never
in the foreground during a session**. See `ARCHITECTURE.md`.

It also carries the lab's **ground-truth** channel: a participant scans a printed session/zone QR
on the way in and again on the way out. That is the only thing in the app that records a *person*
rather than a phone, which is exactly why it is an explicit human act and not a beacon heuristic —
phone-vs-person bias is a quantity the programme measures, so the truth channel must not be derived
from phone presence. The lab console (home screen → sensing badge) generates the code and shows the
tally; the QR icon beside it is the participant's check-in.

## Is it actually recording?

The failure this app is built against is not a crash. It is a session that runs to completion,
reports itself healthy, and quietly delivered a fraction of what it was told to — the lab-readiness
audit found an overnight capture that had collapsed to 11.6 % delivery for 42 minutes with nothing
logged. So every stream carries a **health state** (`alive / degraded / stale / dead`) with
time-in-state accounting, surfaced on the home screen, on a participant-facing **session status**
screen, and persisted into the session sidecar. "Healthy at the end" and "healthy throughout" are
different claims and the sidecar records both.

Clock alignment is pre-registered as gate **G4**: the analysis fits `unix_ts_ns ≈ a·mono_ns + b` per
recording session and needs **at least two** sync samples or the fold is flagged. The app evaluates
that gate on-device and says so while the operator is still in the room.

## Build

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # iOS
./gradlew :composeApp:assembleDebug                    # Android (minSdk 29)
./gradlew :composeApp:testDebugUnitTest                # pure-logic tests (health, backoff, clock, QR, zones)
open iosApp/iosApp.xcodeproj                           # iOS app (Firebase via SPM, resolved by Xcode)
```

`commonTest` covers the pure objects only — no coroutines, no database, no Compose. Note that
`:composeApp:iosSimulatorArm64Test` cannot **link** from Gradle: the test binary wants
`FirebaseMessaging`, which is resolved by Xcode/SPM rather than by the Gradle build. The same tests
compile for Kotlin/Native (`:composeApp:compileTestKotlinIosSimulatorArm64`) and run on the JVM.

Firebase is optional and off unless `composeApp/google-services.json` is present.

## Configuration

Runtime configuration comes from the backend (`GET /api/lab/config`) and is cached locally; the API
base URL is `core/config/AppConfig.kt`. For a bench rig the backend does not know about yet, the
**lab console** (home screen → sensing badge) can override the collector by hand.

## iOS capabilities required

`UIBackgroundModes: location, bluetooth-central`, **Always** location authorization, the
`HotspotConfiguration` and `wifi-info` entitlements, and a paid developer account. Always
authorization is not optional: "When In Use" is revoked the moment the app is backgrounded, which
is the only state a session actually runs in.

Onboarding asks for Always explicitly as its own step, and every permission screen states the
**consequence of refusing** rather than the platform rule behind the ask ("the session stops
recording as soon as you put the phone away", not "location is required for Bluetooth scanning").
Participants are students; the copy lives in `core/domain/permissions/LabPermission.kt` and is
shared between onboarding and the repairable checklist on the session-status screen.
