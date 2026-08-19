# monad-app

The MonadCount **participant instrument**: a Kotlin Multiplatform (Compose) app that turns a phone
into part of the measurement apparatus for the lab's wireless-sensing experiments.

A phone here plays several roles at once — **illuminator** (emits a paced UDP stream from a station
associated to an experiment AP, giving the fleet's CSI receivers a uniform sounding source),
**broadcaster** (advertises a moving, session-scoped identity the fleet's passive BLE scan can hear),
**witness** (observes surveyed iBeacon anchors and reports zone transitions), and **subject** (a
body in the room). It never captures CSI: no mobile OS exposes it.

**iOS is the first-class target.** A session has one of two postures and they are opposites: a *field*
session is a phone in a pocket with the screen off, and a *walk* is somebody holding one, because both
of a walk's roles are foreground-only on iOS. See `ARCHITECTURE.md`.

## The walk

The lab console runs one thing: a **measurement walk**. The phone advertises a session identity the
fleet can hear, records **where it was** while it did, and the operator marks surveyed waypoints that
tie the two coordinate frames together. That is what a fingerprinting corpus needs — a fingerprint is
a mapping from a place to a signal, and until the pose track existed the app recorded only the signal.

Position comes from visual-inertial odometry (ARKit world tracking on iOS; not yet implemented on
Android, which needs a GL Activity). The frame is **session-local**: the origin is wherever tracking
started, so the numbers are metres relative to that, and scanned waypoints are what convert them into
the building's frame. Three waypoints determine the transform and bound the drift between them; fewer
than three and the walk has a shape with no place.

On a LiDAR iPhone the walk also **exports the room**: `mesh.ply`, binary PLY, in the same frame as the
trajectory, carrying ARKit's per-face labels (wall / floor / ceiling / table / seat / window / door) when
the device supplies them. The labels are the point rather than a bonus — the ray-traced channel simulator
wants materials, and an unlabelled mesh gives every wall and every seat the same permittivity.

Geometry with no time cannot be laid on a radio capture, so `mesh.tsv` is a **change log**: when each
block first appeared and each time it changed, on the same clock as everything else. A block that changed
*after* the walk had passed it means somebody moved a chair, and the exported mesh is then only valid for
the window after that change. Nothing else records that.

And a walk now disciplines its clock over `GET /api/lab/time`, so `mono_ns` maps onto the Unix epoch the
fleet's `csid` nodes are chrony-disciplined to. That endpoint had existed since the lab stack was written
and the app had never called it — which meant a walk produced a trajectory, a mesh and an identity frame
on a device-local timeline nobody else shared. `ARCHITECTURE.md` has the full chain from a triangle to a
CSI record.

The number to read while walking is **path length**. Look at the corridor, look at the readout: forty
metres that came back as four is a tracker that never initialised, and nothing else on the phone
catches that while it is still free to fix. The second is **faces**: a mesh that stops growing halfway
through means the LiDAR is looking at something it cannot resolve, and the fix — walk that stretch again,
slower — is only available while you are still standing there.

The illuminator role has no operator panel, and that is hardware rather than a decision — the fleet's
AX210 cannot enter AP mode, so there is nothing for a phone to associate to and therefore no
collector, no pinned socket and no UDP clock exchange. The code is untouched and still reachable from
a quest.

It also carries the lab's **ground-truth** channel: a participant scans a printed session/zone QR
on the way in and again on the way out. That is the only thing in the app that records a *person*
rather than a phone, which is exactly why it is an explicit human act and not a beacon heuristic —
phone-vs-person bias is a quantity the programme measures, so the truth channel must not be derived
from phone presence. The QR icon on the home screen is the participant's check-in.

A check-in and a **waypoint** are deliberately different things. A check-in says "a person crossed
into this zone" and feeds the occupancy count. A waypoint says "the phone was at this printed point at
this instant" and feeds geometry. Recording one as the other would put a position fix into the tally
the whole calibration rests on.

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
**lab console** (home screen → sensing badge) can override the advertise namespace by hand — the one
value that decides whether the fleet can attribute anything it hears to this handset.

The numbered waypoint cards (`MONAD-FP-01`…`MONAD-FP-20`) are printed from
`infra/labels/markers.toml` in monad-knowledge. The app **mirrors** that template rather than owning
it: the card on the wall is the contract, and a card's location is not printed on it — it lives in the
placement record, so the set can be re-laid between arms without a reprint.

## iOS capabilities required

`UIBackgroundModes: location, bluetooth-central`, **Always** location authorization, the
`HotspotConfiguration` and `wifi-info` entitlements, camera access (the printed marker codes, and the
walk's own odometry), and a paid developer account. Always
authorization is not optional: "When In Use" is revoked the moment the app is backgrounded, which
is the only state a session actually runs in.

Onboarding asks for Always explicitly as its own step, and every permission screen states the
**consequence of refusing** rather than the platform rule behind the ask ("the session stops
recording as soon as you put the phone away", not "location is required for Bluetooth scanning").
Participants are students; the copy lives in `core/domain/permissions/LabPermission.kt` and is
shared between onboarding and the repairable checklist on the session-status screen.
