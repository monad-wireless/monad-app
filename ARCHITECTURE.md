# monad-app architecture

Kotlin Multiplatform (Compose Multiplatform) app that turns a phone into a **lab instrument** for
the MonadCount wireless-sensing programme. iOS is the first-class target; Android follows.

## The idea

The lab is described role-first: an experiment is an assignment of roles to devices plus a
schedule. A phone is the one device that holds several roles at once.

| Role | Contract | Implementation |
|---|---|---|
| **Illuminator** | Emit frames at a *commanded* pace and report the pace actually delivered | `TrafficGenerator` over a `LabDatagramSocket` pinned to the experiment AP |
| **Broadcaster** | Advertise a *moving*, session-scoped identity the fleet's passive BLE scan can observe. Unlike an anchor the position is the unknown, and the fleet's per-node RSSI on the frame is what localises it | `IdentityBroadcaster` — a legacy advertisement carrying one 128-bit service UUID, the only shape every platform can emit and every raw scanner can read |
| **Witness** | Observe surveyed anchors and report RSSI + zone transitions, *without being in the foreground* | `BeaconWitness` — CoreLocation on iOS, BLE scan + `IBeaconParser` on Android |
| **Subject** | A body in the room | the participant carrying it |

Two facts shape everything:

1. **The phone never captures CSI.** No mobile OS exposes it. CSI comes from the `csid` fleet
   nodes; the phone's job is to be a well-characterised *transmitter* and *reporter*.
2. **A session has one of two postures, and they are opposites.** A *field* session is a phone in a
   pocket with the screen off, and anything measured in the foreground measures a condition that will
   never occur. A *walk* is the reverse: it is somebody holding a phone, deliberately, because both
   of its roles are foreground-only on iOS — advertising moves into an overflow area no raw scanner
   can parse when backgrounded, and ARKit pauses outright. Neither reports it when it happens, which
   is why `ForegroundWake` holds the screen for the length of a walk. The auto-lock timer would
   otherwise end every walk about thirty seconds in, silently.

On iOS the pocketed posture combines into one mechanism: a CoreLocation beacon session both witnesses
the anchors *and* buys the process the background runtime the emitter needs. Residency and witnessing
are not two features.

### The walk

The session shape a fingerprinting corpus needs, and the one the lab console drives. The phone
advertises a session identity, records **where it was** while it did, and the operator marks surveyed
waypoints that tie the two coordinate frames together.

| Piece | What it is | Why it is not something simpler |
|---|---|---|
| `PoseTracker` | Visual-inertial odometry, sampled at a *commanded* rate. ARKit world tracking on iOS, polling `session.currentFrame` — no view controller, because a pose needs no rendering. Not implemented on Android: ARCore's `session.update()` is only legal on the thread holding the camera texture, so it needs a GL Activity | A zone label from an anchor is room-sized. A scanned marker is one point at one instant. A fingerprint is a mapping from a *place* to a signal, so it needs the line between the points |
| `pose.tsv` | Position, orientation quaternion, and a **per-sample** tracking-quality column | Odometry does not fail loudly — it keeps returning plausible positions while lost. Without per-sample trust, a track's bad segments are indistinguishable from its good ones and the whole walk has to be discarded |
| `waypoint` markers | A scanned card code plus the pose held at the instant of the scan | Three correspondences determine the transform into the site frame *and* bound the drift between them. Storing only the code would leave a reader interpolating `pose.tsv`, discarding the one moment both frames were observed together |
| `LabStream.POSE` | The pose stream in the health monitor, with a commanded rate | The second stream with a rate to fall short of, and the more insidious one. A thermally throttled phone delivers fewer poses; a commanded rate is what turns that into a number instead of a slightly sparser file |
| `mesh.ply` | The room as triangles — binary PLY, in the pose frame, with per-face ARKit semantic labels when the device supplies them | OBJ cannot carry a per-face label at all, which would mean shipping the classification as a side-car array whose row order silently has to match the face order. The labels are the point: the ray-traced channel simulator wants **materials**, and an unlabelled mesh gives every wall and every seat the same permittivity |
| `mesh.tsv` | A **change log**: when each block first appeared and each time its geometry changed | ARKit publishes no timestamps and no revision counter, so a mesh has no history unless one is written down. A periodic dump would bury the rows that matter under thousands of identical ones. A block that changed *after* the walk passed it means the room moved, and the exported mesh is then valid only for the window after that change |
| `SessionBlobRecord` | A place to persist a binary artefact at all | Closes a gap older than the mesh: `SensorCapture` has carried an optional `payload: ByteArray` since the sensor modules were written, with nowhere to put it. Persisted rather than held in memory because the rule is upload-then-delete, and an artefact that lives only in the process is lost the first time an upload fails — the normal case on an experiment network |
| `ReferenceClock` → `LabTimeGateway` | Four-timestamp clock discipline over `GET /api/lab/time` | The endpoint had existed on the backend since the lab stack was written and **the app never called it**. Without it a walk had no `clock.tsv` at all, because the only other path is the collector's UDP socket and a walk has no collector |

#### How a triangle reaches a CSI record

This is the chain the whole session shape exists to close, and every link is a file:

```
vertex in mesh.ply ─┐
pose in pose.tsv   ─┼─ one frame: session-local, gravity-aligned, origin where tracking started
waypoint pose      ─┘        │
                             │  waypoint markers: printed card ⋈ pose at the scan
                             ▼
                        site frame (PostGIS) ── three fixes determine it and bound the drift

mono_ns on every row ── clock.tsv ──▶ Unix epoch ──▶ csid capture window ──▶ CSI record
                                          ▲
                          the fleet's chrony-disciplined clock
```

Break any link and the geometry is decoration. A mesh in anchor space needs transforms the reader does
not have; a mesh with no timestamps cannot be attributed to a window; a walk with no clock sample is a
map of nowhen. So the mesh is transformed into the pose frame on the device, `mesh.tsv` puts it on the
shared monotonic clock, and `clock.tsv` puts that clock on the epoch the fleet shares.

Three constraints are worth stating because they are not obvious:

* **The two clocks.** `ARFrame.timestamp` is on `CLOCK_UPTIME_RAW`, which stops while the device
  sleeps. Every other stream is on `CLOCK_MONOTONIC_RAW`, which does not. The offset between them is
  read at every sample and applied, so a pose carries the instant its frame was captured rather than
  the instant it was polled, and the two bases are never mixed.
* **The camera is not shareable.** ARKit holds the capture session for the whole walk, so the QR
  scanner is refused while tracking — pausing the tracker to scan would put a hole in the trajectory
  at exactly the instant the waypoint is meant to anchor it. Waypoints are typed or tapped instead.
* **The mesh is exported before the tracker is stopped, and the frame is retained before the session is
  paused.** Pausing discards nothing the export holds, but reading a *running* session's Metal buffers
  means reading memory ARKit may be rebuilding on its own queue as an anchor is refined. So
  `snapshotMesh` takes the frame, pauses, then reads. Reading after `stop()` would return an empty room
  every time; reading before the pause would race.

#### What the HTTP clock is honestly worth

`GET /api/lab/time` is coarser than the collector's UDP exchange and the backend's own documentation
says so — TLS and keep-alive jitter that the minimum-delay filter cannot remove. Against the
pre-registered budgets: **G4a (6 s)** is cleared by two orders of magnitude, and **G4b (250 ms)** is
cleared on a local network but without the margin the UDP path has. For attributing a half-second CSI
window to a position it is far inside the noise — a body at 1.4 m/s moves 1.4 cm in 10 ms. It is
sufficient for a walk and is **not** a substitute for the collector exchange in a session that has one;
the estimate records which path produced it and the sidecar carries that.

The illuminator role has **no operator panel** on this deployment, and that is a hardware fact rather
than a decision about the app: the fleet's AX210 cannot enter AP mode (the Intel LAR firmware limit),
so there is nothing for a phone to associate to, therefore no collector, no pinned socket and no UDP
clock exchange. `TrafficGenerator` and `ClockSyncService` are untouched and still reachable from a
quest; the console simply stopped offering four panels that described an impossible session.

## Start-up order (`LabInstrument.start`)

The order is the order of the experiment's gates, and a failure at any step aborts with a reason
rather than degrading silently:

1. **Residency** — background session acquired first; without it nothing is worth measuring.
1b. **Screen hold** — for a session playing a foreground-only role (broadcast or track). See
   `ForegroundWake`.
2. **Association** — join the commanded AP.
3. **Pinning** — open the socket bound to *that* interface, and record whether pinning took.
   An unpinned socket is the worst failure mode in the system: the UI says connected, the datagrams
   leave over cellular, the observer sees nothing.
4. **Clock** — discipline against the collector before any sample is stamped.
5. **Emission and witnessing.**

## Is the session actually being recorded?

Every failure mode of this instrument is silent by nature. An unpinned socket still "sends"; a
revoked authorization still leaves the app running in the foreground; a throttled background
process still reports its *commanded* rate. The lab-readiness audit's case was worse than any of
those: an overnight capture ran to completion, logged nothing, and had collapsed to **11.6 %
delivery for 42 minutes**.

So liveness is modelled explicitly, in `lab/domain/health/`:

| Piece | Role |
|---|---|
| `StreamState` | `not_applicable · idle · alive · **degraded** · stale · dead`. `degraded` is the state the whole package exists for — producing, so nothing looks broken, but far below the commanded pace. |
| `StreamPolicy` / `StreamPolicies` | Thresholds derived from the commanded configuration, not hard-coded seconds. Silence beyond 20 commanded periods is stale, 100 is dead (with floors and caps); a producing stream below half its commanded rate is degraded, never during the first rate-window of warm-up. |
| `StreamHealthTracker` | Fed by **polling the counters the instrument already keeps**, once a second — never by a callback inside the send loop. Accrues *time in each state*, so "healthy at the end" and "healthy throughout" stay different claims. |
| `SessionHealthMonitor` | Chooses the applicable streams from the session request. A witness-only participant has no illuminator and no clock, and reporting those as dead would be a false alarm that teaches the operator to ignore the panel. |

The result is a `StateFlow<InstrumentHealth>` on `LabInstrument`, shown on the home card and the
participant-facing `SessionStatusScreen`, and written into the sidecar as `health[]` — including
`degraded_ms` / `stale_ms` / `dead_ms` per stream.

## Clock discipline

Four-timestamp SNTP-style exchange over the **same socket the data stream uses**, reduced by
keeping the **minimum-delay** sample (queueing delay is one-sided noise, so averaging would be a
bias rather than a wobble). Bursts at start/mid/end give skew.

### Gate G4

The pre-registration fits `unix_ts_ns ≈ a·mono_ns + b` **per `recording_session_id`** over that
session's `clock.tsv` samples. Fewer than two usable samples and the fit degenerates to offset-only
and the fold is flagged; none and the fold is excluded outright. Three consequences are built in:

1. **`ClockGate` evaluates the gate on-device.** A recentred least-squares fit (raw monotonic
   nanoseconds are order 1e15 and would lose the signal to cancellation in a `Double`), reported as
   skew in ppm plus a fit residual. The residual is a *proxy* and is named `max_fit_residual_ms`,
   never the gate residual — the registered estimand is measured against the fleet's CSI timeline,
   which this device cannot see. A large value proves failure; a small one does not prove a pass.
2. **The second burst is pulled forward.** At the default 600 s resync a session shorter than ten
   minutes would ship exactly one sample and be silently downgraded, so the second burst fires a
   minute in and the cadence settles afterwards.
3. **Each burst leaves a `clock_sync` marker.** G4's residual is measured *at* sync markers and the
   pre-registration asks for ≥ 4 per fold; deriving them from the resync cadence beats relying on an
   operator to fire them.

The offset is **stored, never applied**. The device does not rewrite its own timestamps; analysis
applies the correction, which keeps it auditable and lets a better estimate be substituted later.
Monotonic sources are the sleep-continuous ones (`CLOCK_MONOTONIC_RAW`, `elapsedRealtimeNanos()`) —
the uptime clocks stop during sleep and would silently compress a backgrounded session.

### `mono_ns` resets, and that must never be silent

The continuity epoch (`clockBootId()`) is stored **on the session row**. A session found still
`open` at launch was interrupted, and `LabSessionRecovery` closes it — but stamps `endedMonoNs`
only when the epoch matches. Across a reboot it writes wall time alone and sets
`monotonic_continuous = false`, because a monotonic reading from the current epoch is not on the
same timeline as that session's samples and filling it in would weld two timelines together in the
column the analysis joins on.

Every data packet already carries `{session_id, seq, t_mono_ns}`, so the paced stream *is* the sync
stream: offset drift stays observable continuously and the delivered-rate timeline falls out of the
same records.

## Ground truth — the people channel

Every stream above counts **phones**. The calibration this instrument feeds needs **people**, and
the gap between the two is not noise: phone-vs-person bias is the quantity a later experiment sets
out to measure. A truth channel derived from phone presence would therefore be circular, so the one
here cannot be derived from it at all — it only advances when a participant deliberately scans a
printed session/zone code.

| Piece | Role |
|---|---|
| `GroundTruthQr` | Codec for the printed code: `monad://ground-truth/v1?session=…&zone=…&site=…&dir=in\|out\|toggle`. Pure, so both ends of the round trip are testable without a camera. `parse` returns a classified `QrScan` — not-ours / wrong-version / malformed each need a different sentence in a doorway, and a single null cannot carry that. |
| `GroundTruthRecorder` | Resolves `toggle` against the participant's own last scan for that zone, stamps `monoNs` + `wallMs`, writes. Reads the instrument's session id and touches nothing else. |
| `GroundTruthRepository` | SQLite buffer (`GroundTruthEventRecord`) + the `ground_truth.tsv` renderer. |
| `ZoneMembership` / `ScanPlanner` | Three zones in one hall, and the rule that **entering a zone leaves the previous one**. Occupancy is a cumulative sum of `direction` per `(lab_session_id, zone_id)`; a participant who walks A → B and scans only B's code would leave A one person too high for the rest of the session, invisibly. The implied `out` is a real event written as an ordinary row — nothing here ever manufactures an `in` a human did not scan. Also owns duplicate-scan debouncing and the "this code is for another session" case. |
| `GroundTruthScanScreen` | Participant surface. Camera via the same QRKit + moko-permissions boundary the quest QR step uses. Which zone you are in is the first thing on the screen; a toggle code is only trustworthy if the person scanning it can see which way it counted them. |

Two properties are deliberate. The event is keyed by the **scanned** lab session id, not by this
phone's recording id, so a participant who arrives before the operator starts the run is still
counted. And the identity is the same opaque pseudonym the sidecar carries — the dataset learns that
*someone* entered a zone, never who.

## Data

Sessions record to SQLite (`LabSessionRecord` + narrow per-stream tables: traffic, beacons, zone
transitions, clock, markers, health checkpoints, pose, mesh observations, and a blob store for binary
artefacts), export as TSV plus a `metadata.json` sidecar
mirroring the CSIQ session block, and upload to
`datasets/monad-app-sessions/<participant>/<session>/`. Streams go up before the sidecar, always: the
sidecar's presence is the contract the reader side uses to detect a partial upload.

### Which build produced this recording

The sidecar is `monad-app/session-sidecar/v5`. `environment.app_version` is the marketing version
(`1.2.0`) and `environment.build_id` identifies the binary:

```
build_id := <version> "+" <versionCode> ".g" <commit8> [ ".dirty" <worktree8> ]
example  := 1.2.0+5.g0940fc0b.dirty586d603e
```

`<worktree8>` is a content hash of the uncommitted change, present only when the build was made
from a dirty tree — so two bench builds of the same version from different patches are still
distinguishable, and a build that is not reproducible says so.

All of it derives from `monad.version` / `monad.versionCode` in `gradle.properties`, the only place
a version is written by hand. Android's `versionName`/`versionCode` are assigned from those
properties; `BuildIdentity.kt` is generated from them into `commonMain`, so both platforms compile
the same constants; and iOS — whose build settings Xcode resolves before Gradle ever runs — is
*checked* by `:composeApp:verifyIosAppVersion`, which every `compileKotlinIos*` task depends on and
which fails the build when `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` disagree.

Up to v3 `app_version` carried a hand-maintained string (`0.3.0-lab`) that no build system used, so
it identified nothing; a reader must not treat a v3 and a v4 `app_version` as the same kind of
value. The identity is stamped onto `LabSessionRecord` at session **open**, not derived at close,
because an interrupted session's sidecar is assembled on a later launch that may be a different
build entirely.

Ground truth rides the same transport but under the **scanned** session id, so every participant's
phone writes its own `ground_truth.tsv` under its own pseudonym prefix and the collection side
unions them. Each flush re-renders the participant's complete set for that session — whole-file
replace, so a session that flushes three times ends with all of it rather than the last fragment.
Rows are marked sent, never deleted.

**Upload-then-delete**: local data is released only after the server acknowledges every artefact.
A failed upload marks the session `failed`, keeps every byte, and surfaces it in the lab console as
unsynced.

**Bounded retry, and a report instead of a count.** Each artefact gets a small budget of attempts
with geometric backoff (`RetryPolicy`, ~13 s of waiting in the worst case). Both bounds matter: a
phone joined to an experiment AP normally has no route to the internet at all, so retrying forever
would burn an hour of battery on a link that cannot succeed until the participant leaves the room —
while a single attempt would fail a whole session on one Wi-Fi handover. A flush returns a
`FlushReport` naming every artefact, its row count, its attempts, and a `discarded` counter that is
zero by construction and displayed anyway; the old `Int` return could not distinguish "nothing to
send" from "everything failed", and those call for opposite actions. `PendingInventory` breaks the
backlog down per artefact, because "3 sessions pending" and "412 000 traffic rows and 2 scans" are
different facts to somebody deciding whether to wait for Wi-Fi.

**Two destinations for ground truth.** The S3 TSV is the science; `POST /api/lab/ground-truth` is
an operational convenience that lets the operator console show a *room* count instead of one
handset's count. They are acknowledged separately (`uploaded` vs `ingested`) because they fail
independently, and the aggregate can never gate, delay, or fail the artefact. A server answer is
final for that batch — `duplicates` is a success, since idempotency is a unique index on
`scan_nonce` and re-sending a session's complete set is the intended behaviour — and `conflicts`
(pre-registration exclusion E3) are surfaced rather than swallowed, because by analysis time the
affected interval is already excluded and nobody is left to ask what happened.

**Crash, kill and reboot.** A session left `open` is invisible to every upload path —
`selectPendingUpload` takes only `closed` and `failed` — so before `LabSessionRecovery` existed an
OS kill during a backgrounded session stranded the most expensive artefact in the system on the
device, permanently and silently. Recovery runs on the first screen of every launch, closes such
sessions with an `interrupted_reason`, and queues them like any other.

## Module map

```
lab/
├── domain/      LabRole, LabConfig, LabPacket, LabClock, ClockSyncService, TrafficGenerator,
│                LabDatagramSocket, BeaconWitness/ZoneTracker, IBeaconParser,
│                BackgroundResidency, LabSession (sidecar), LabInstrument,
│                LabInstrumentState (SessionRequest/Phase), LabEnvironment (expect),
│                ClockGate (gate G4 on-device), SessionReport (the screenshot),
│                GroundTruthEvent/GroundTruthQr, GroundTruthRecorder,
│                ZoneMembership/ScanPlanner (three-zone traversal, implicit exits)
│                — ports: SessionRecorder, GroundTruthStore
│   ├── health/  StreamState, StreamPolicies, StreamHealthTracker, SessionHealthMonitor
│   └── upload/  RetryPolicy, FlushReport, TallyOutcome, PendingInventory, ArtefactSink (port)
├── data/        LabConfigService (GET /api/lab/config, cached),
│                LabSessionRepository : SessionRecorder,
│                LabSessionUploader, LabSessionRecovery (crash/kill/reboot),
│                GroundTruthRepository : GroundTruthStore,
│                GroundTruthTallyService (POST/GET /api/lab/ground-truth — the room-wide tally)
├── presentation/ LabConsoleScreen — the operator surface
│                SessionStatusScreen — "am I recording?", the participant surface
│                GroundTruthScanScreen — the participant check-in surface
└── service/     (androidMain) LabSessionService foreground service

quests/
├── domain/      QuestSessionCoordinator — the single place a quest becomes a lab session and the
│                single place a finished quest is submitted + uploaded (submit → upload → purge)
│   └── port/    LabBundleSource, LabSessionArchive, ParticipantDirectory,
│                QuestCompletionGateway, QuestStepJournal, and their value types
│                (QuestParticipant, QuestSkip, QuestStepRecord, QuestStepOutcome, QuestCompletion)
├── data/
│   ├── dto/     the wire types: TaskType/TaskDto/QuestDetailDto/ActiveTaskDto + configs +
│   │            TaskConfigParser, QuestCompleteRequestDto/SkipRecordDto, QuestStartResponseDto
│   ├── adapter/ the five port implementations, over UserRepository, QuestsService,
│   │            LabConfigService, LabSessionUploader/Repository, QuestStepCompletionRepository
│   └── repository/ QuestStepCompletionRepository (SQLDelight)
└── presentation/ quest list, detail, active quest, step components
```

Platform actuals live in `androidMain`/`iosMain` under the same package as their `expect`.

### The boundaries that are enforced

Dependencies point inward — `domain` ← `data` ← `presentation` — and inside `lab/` and `quests/`
that is a test, not a convention. `LabInstrument` and `GroundTruthRecorder` name **ports**
(`SessionRecorder`, `GroundTruthStore`, `ArtefactSink`) rather than the SQLDelight repositories that
implement them, and `LabBoundaryTest` fails the build on any `import …lab.data` inside `lab/domain`.

`QuestsBoundaryTest` is the same rule on the participant path, and for the same reason.
`QuestSessionCoordinator` used to name six concrete collaborators from four features' data layers,
so the one rule that path cannot recover from — *submit, upload, and only then purge* — sat in a
class that could also reach `forceDelete`, `deleteAll` and `purgeUploaded`. It now names the five
ports in `quests/domain/port`, implemented by adapters in `quests/data/adapter`; the test fails on
any `.data.` import inside `quests/domain`, and on any `@Serializable` type there (the wire DTOs
that used to live in that package now live in `quests/data/dto`, where the backend's shapes belong).

`auth/domain/AuthManager` is deliberately *not* held to this rule: it names its own feature's
repository and service, which is an ordinary use-case object, and inverting it would mean replacing
the SQLDelight `User` type across the auth and my-account screens — a redesign of the auth read
model rather than a re-placement of a file.

The reason is narrower than tidiness. `LabSessionRepository` can `purgeUploaded`, `forceDelete` and
`markUploaded`; the measurement path must never be able to call any of them, because releasing data
is the uploader's rule and the instrument's job is only to write. A port is the smallest way to say
that in the type system, and the test is what keeps one convenient import from undoing it.

`SessionRecorder` and `GroundTruthStore` are Koin **aliases**, not second instances —
`single<SessionRecorder> { get<LabSessionRepository>() }` — so there is still one database handle
and one write path.

## The lab console

Reachable from the home screen. Panels follow the start-up order — residency, collector/binding,
roles, control, illuminator, clock, witness, ground truth, sessions, log — so reading top to bottom
walks the same sequence the instrument does, and the first red line is the one that matters. The
ground-truth panel sits directly under the witness panel so the operator reads the phone count and
the people count next to each other; the gap between them is the point. Every failure
mode here is silent by nature; the console exists so they become visible on a bench in seconds.

### The room tally, and knowing which number you are looking at

A handset only ever sees its own participant's scans, so the local ground-truth count is not a
room count — with ten to twelve participants it is a different quantity, not a small error. The
room number comes from `GET /api/lab/ground-truth/{labSessionId}`, polled every 4 s while a session
is running.

The panel therefore always labels its own provenance: **IN ROOM (all devices)** with the age of the
last successful poll, **IN ROOM — STALE** once that exceeds 15 s, or **THIS DEVICE ONLY** when the
backend has never answered for this session. The last good snapshot is kept and aged rather than
blanked on the first timeout — a phone on an experiment AP usually has no route to the internet, so
a failed poll is the normal case. A null poll means "no fresh number", never "zero people".

The manual tally sheet stays on screen in every mode. It is the pre-registered redundant check, it
catches the failure the electronics cannot see, and it is what makes a stale tally survivable.

## The participant's surface

The console is for whoever is running the session. Everyone else is carrying a phone in a pocket for
three hours, and their entire interaction is unlocking it and needing one sentence: *yes you are
recording · you are in ZONE-B · last event 4 s ago*. That sentence is the home card, and
`SessionStatusScreen` is the same answer with its working shown — per-stream liveness, the clock
gate in plain words, a permission checklist that can be repaired in place, the backlog per artefact,
and the last session's summary.

Its headline changes when a stream dies, not only when the session stops. An instrument that reports
"recording" while a stream is dead is the exact failure this design exists to make impossible, and
putting that in a detail row nobody scrolls to would reintroduce it.

Permission copy comes from `core/domain/permissions/LabPermission.kt` and states the **consequence
of refusing** rather than the platform rule behind the ask. Onboarding and the status checklist read
the same strings, so there is one place to fix a sentence a student found confusing.

## What is tested

`commonTest` covers the pure objects, and only those: health thresholds and time-in-state, the
retry/backoff schedule, the clock fit and gate statuses, the QR codec's four failure classes, the
three-zone traversal, and the flush/tally report semantics. Every one of them was extracted from a
coroutine, a socket or a database specifically so that it could be checked without a lab — the value
is not coverage, it is that these are the decisions whose failure modes are invisible in the field.

`androidUnitTest` is where a test is allowed a database (`LabSchemaMigrationTest`,
`LabSessionRecoveryTest`, `LabSessionUploaderTest`) and, additionally, the two structural guards
that have no runtime of their own:

| Guard | What it would have caught |
|---|---|
| `AppModuleGraphTest` | Koin resolves lazily, so a definition whose constructor gained an unprovided parameter compiles and then throws on the screen that first needs it. `verify()` walks every constructor by reflection without instantiating anything. |
| `LabBoundaryTest` | An `import …lab.data` inside `lab/domain`, i.e. the measurement path regaining the ability to delete its own data. |

`:composeApp:iosSimulatorArm64Test` cannot **link** from Gradle (the test binary wants
`FirebaseMessaging`, resolved by Xcode/SPM); the same sources compile for Kotlin/Native via
`:composeApp:compileTestKotlinIosSimulatorArm64` and run on the JVM via `:composeApp:testDebugUnitTest`.

`src/commonMain/sqldelight/databases/10.db` is the committed schema snapshot. Without it
`verifySqlDelightMigration` had nothing to compare against and passed unconditionally — which is
how `sqldelight { version }` was able to drift twice. With it, a `.sq` change that no migration
performs fails the build by name.

## Configuration

Nothing about a deployment is compiled in. SSIDs, the collector endpoint, the beacon UUID and zone
map, and the traffic profiles all arrive from `GET /api/lab/config` and are cached locally, because
a phone joined to an experiment AP normally has no route to the internet. The console can override
the collector by hand for a bench rig the backend does not know about yet.

## Build

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # iOS (first-class target)
./gradlew :composeApp:assembleDebug                    # Android
./gradlew :composeApp:testDebugUnitTest                # pure-logic tests
```

Firebase is optional: `google-services.json` carries per-deployment secrets and is not in the
repository, so its plugins are applied only when the file is present. Crash reporting and push are
not needed to build or to run a session.

`minSdk` is 29 — `WifiNetworkSpecifier` and `Network.bindSocket` are the association and pinning
primitives the instrument is built on.
