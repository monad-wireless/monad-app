# monad-app architecture

Kotlin Multiplatform (Compose Multiplatform) app that turns a phone into a **lab instrument** for
the MonadCount wireless-sensing programme. iOS is the first-class target; Android follows.

## The idea

The lab is described role-first: an experiment is an assignment of roles to devices plus a
schedule. A phone is the one device that holds three roles at once.

| Role | Contract | Implementation |
|---|---|---|
| **Illuminator** | Emit frames at a *commanded* pace and report the pace actually delivered | `TrafficGenerator` over a `LabDatagramSocket` pinned to the experiment AP |
| **Witness** | Observe surveyed anchors and report RSSI + zone transitions, *without being in the foreground* | `BeaconWitness` — CoreLocation on iOS, BLE scan + `IBeaconParser` on Android |
| **Subject** | A body in the room | the participant carrying it |

Two facts shape everything:

1. **The phone never captures CSI.** No mobile OS exposes it. CSI comes from the `csid` fleet
   nodes; the phone's job is to be a well-characterised *transmitter* and *reporter*.
2. **The app is never in the foreground during a session.** A field session is a phone in a pocket,
   screen off. Anything measured in the foreground measures a condition that will never occur.

On iOS those combine into one mechanism: a CoreLocation beacon session both witnesses the anchors
*and* buys the process the background runtime the emitter needs. Residency and witnessing are not
two features.

## Start-up order (`LabInstrument.start`)

The order is the order of the experiment's gates, and a failure at any step aborts with a reason
rather than degrading silently:

1. **Residency** — background session acquired first; without it nothing is worth measuring.
2. **Association** — join the commanded AP.
3. **Pinning** — open the socket bound to *that* interface, and record whether pinning took.
   An unpinned socket is the worst failure mode in the system: the UI says connected, the datagrams
   leave over cellular, the observer sees nothing.
4. **Clock** — discipline against the collector before any sample is stamped.
5. **Emission and witnessing.**

## Clock discipline

Four-timestamp SNTP-style exchange over the **same socket the data stream uses**, reduced by
keeping the **minimum-delay** sample (queueing delay is one-sided noise, so averaging would be a
bias rather than a wobble). Bursts at start/mid/end give skew.

Every data packet already carries `{session_id, seq, t_mono_ns}`, so the paced stream *is* the sync
stream: offset drift stays observable continuously and the delivered-rate timeline falls out of the
same records.

The offset is **stored, never applied**. The device does not rewrite its own timestamps; analysis
applies the correction, which keeps it auditable and lets a better estimate be substituted later.
Monotonic sources are the sleep-continuous ones (`CLOCK_MONOTONIC_RAW`, `elapsedRealtimeNanos()`) —
the uptime clocks stop during sleep and would silently compress a backgrounded session.

## Data

Sessions record to SQLite (`LabSessionRecord` + three narrow sample streams), export as TSV plus a
`metadata.json` sidecar mirroring the CSIQ session block, and upload to
`datasets/monad-app-sessions/<participant>/<session>/`.

**Upload-then-delete**: local data is released only after the server acknowledges every artefact.
A failed upload marks the session `failed`, keeps every byte, and surfaces it in the lab console as
unsynced.

## Module map

```
lab/
├── domain/      LabRole, LabConfig, LabPacket, LabClock, ClockSyncService, TrafficGenerator,
│                LabDatagramSocket, BeaconWitness/ZoneTracker, IBeaconParser,
│                BackgroundResidency, LabSession (sidecar), LabInstrument
├── data/        LabConfigService (GET /api/lab/config, cached), LabSessionRepository,
│                LabSessionUploader
├── presentation/ LabConsoleScreen — the operator surface
└── service/     (androidMain) LabSessionService foreground service

quests/          the schedule engine; QuestSessionCoordinator is the single place a quest becomes a
                 lab session and the single place a finished quest is submitted + uploaded
```

Platform actuals live in `androidMain`/`iosMain` under the same package as their `expect`.

## The lab console

Reachable from the home screen. Panels follow the start-up order — residency, collector/binding,
roles, control, illuminator, clock, witness, sessions, log — so reading top to bottom walks the
same sequence the instrument does, and the first red line is the one that matters. Every failure
mode here is silent by nature; the console exists so they become visible on a bench in seconds.

## Configuration

Nothing about a deployment is compiled in. SSIDs, the collector endpoint, the beacon UUID and zone
map, and the traffic profiles all arrive from `GET /api/lab/config` and are cached locally, because
a phone joined to an experiment AP normally has no route to the internet. The console can override
the collector by hand for a bench rig the backend does not know about yet.

## Build

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # iOS (first-class target)
./gradlew :composeApp:assembleDebug                    # Android
```

Firebase is optional: `google-services.json` carries per-deployment secrets and is not in the
repository, so its plugins are applied only when the file is present. Crash reporting and push are
not needed to build or to run a session.

`minSdk` is 29 — `WifiNetworkSpecifier` and `Network.bindSocket` are the association and pinning
primitives the instrument is built on.
