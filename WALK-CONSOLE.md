# The walk console — design and operating protocol

The lab console is the operator surface for a **measurement walk**: the phone advertises a session
identity the fleet's passive BLE scan hears, records where it was while it did, scans the room's
geometry, and marks surveyed points. This document says why the console looks the way it does and
how to run the two walk shapes it supports. The evidence behind every rule is `walk-fp-01`
(2026-08-19), the first occupied run, whose two walks are dissected in the monad-knowledge diary of
that date.

## What the instrument is for — roles, restated

| Channel | Instrument | Trust |
|---|---|---|
| Identity / position over time | Phone BLE advertising → fleet's per-node RSSI | Primary. Reconstructed both real walks at 0.73 m/s median with zero impossible hops. |
| Geometry / positioning | **LiDAR** — the ARKit world map (on-device relocalisation) and `mesh.ply` (offline registration to the surveyed floor) | Primary for position. The mesh is metric and registers against the floor bundle with a measured residual. |
| Relative motion between fixes | ARKit visual-inertial odometry (`pose.tsv`) | Interpolator only. On walk A it was 35 % trusted; it must never be the sole position source. |
| Frame correspondence | Waypoints (`MONAD-FP-01..20` cards) | Three or more per walk, or the trajectory has a shape and no place. |

The odometry demotion is deliberate and evidence-based: both real walks produced confidently wrong
distances (92 m and 161 m for a barely-moving handset) while the fleet's BLE produced a usable
track. LiDAR is **not** carried for pose detection — it is carried for *localization*: the world
map pins every walk on a site to one persistent frame, and the mesh ties that frame to the
building.

## Why tracking failed, measured

Per-sample forensics on the two real walks:

| | walk A | walk B |
|---|---|---|
| median camera pitch | **−39°** (floor-staring) | **−14°** (near horizon) |
| tracking `normal` | 35.5 % | 60.7 % |
| dominant reason | `initializing` 62 % | `initializing` 38 % |
| frame gaps > 1 s | 24 (max 3.2 s) | 15 (**max 20 s**, then re-`initializing`) |

Camera pitch is the variable that separates the walks, and it is the one thing the operator can
change mid-walk. The operator stared at the console; the camera stared at carpet. Walk B's
twenty-second gap is a session interruption that nothing recorded — ARKit resets or relocalises
silently, and the artefacts only carried the hole.

## The console's answers

1. **Camera preview on the walk panel.** The operator sees what the tracker sees. A phone held so
   the preview shows the room ahead is a phone held at the pitch the tracker needs. Toggleable
   (battery), on by default.
2. **One coaching sentence, live.** Derived from the smoothed pitch (< −30° → "RAISE THE PHONE"),
   the tracking reason (`initializing` past 10 s, `insufficient_features`, `excessive_motion`,
   `relocalizing`) and the jump rate. One sentence at a time, silence when healthy.
3. **A dismissable stop gate.** Closing a tracked walk with fewer than three waypoints, or with an
   untrusted track, puts one dialog in the way that names what the close costs. Dismissable — an
   aborted bench test should not have to invent waypoints — but never silent again.
4. **`pose_stalled` / `pose_resumed` markers.** The stall detector rides the heartbeat; a frame gap
   past max(3 periods, 2 s) becomes a timeline marker carrying the gap width.
5. **Session interruptions are recorded.** The ARKit session now has an observer: interruption,
   resumption (with relocalisation) and failure land in the timeline, the instrument log, and the
   sidecar's lifecycle events. `sessionShouldAttemptRelocalization` answers **true** so a resumed
   session relocalises into the *same* frame instead of silently splitting the artefacts across
   two origins.
6. **The instrument log ships.** Every `note()` line persists to `InstrumentLogRecord` and uploads
   as `log.tsv` — the sentence that explains a failure survives the process that said it.
7. **World-map localization.** At close the session's `ARWorldMap` is serialised and stored per
   session (provenance, uploaded as `worldmap.armap`) and per site (the standing map). The next
   walk on the site loads it and relocalises, so all walks on a site share one frame. First walk
   on a site creates the map; later walks extend it.

## The build a field walk runs on (2026-09-04)

**A field walk runs a Release build, or a Debug build with Xcode's diagnostics switched off.**
This is not hygiene. It is the difference between a walk and a lost walk.

On 2026-09-04 a 14 m 42 s survey walk was destroyed. FrontBoard killed the app with `0x8BADF00D`
— "Failed to terminate gracefully after 5.0s" — as it went to the background. It was not memory
(thermal level 0, no jetsam), and it was not CPU (the main thread was blocked, not spinning).
The faulting stack was one Compose frame going through three interception layers:

```
__ulock_wait2                                               <- blocked on an ObjC side-table lock
objc_object::sidetable_retain(bool)
_replacement_NSObject_conformsToProtocol_Instance_Version   <- libMainThreadChecker.dylib
-[MTLDebugCommandBuffer addPurgeableObject:]                <- Metal API Validation
-[CaptureMTLRenderCommandEncoder drawIndexedPrimitives:...] <- GPU frame capture
GrOpFlushState::drawMesh                                    <- Skia
androidx.compose.ui.window.MetalRedrawer.draw
```

Each layer retains ObjC objects on the main thread. Kotlin/Native's ObjC interop already puts heavy
weak-reference traffic on the same side tables, so the lock is contended by construction. Add the
shims and one draw call can outlast the five seconds iOS gives an app to quiesce.

The cost is the whole session, not one frame. A `SIGKILL` runs no `stop()`, so no sidecar is
written and the row stays `open` — the one status `selectPendingUpload` does not select. The data
survives on the phone and the next launch recovers it, but it is not uploaded until somebody
notices.

Three things now stand between that and a repeat:

| Guard | Where | What it does |
|---|---|---|
| Scheme defaults | `iosApp/iosApp.xcodeproj/.../iosApp.xcscheme` | Main Thread Checker, Thread Performance Checker, Metal API Validation and GPU Frame Capture are switched off for the Debug launch action |
| Preflight blocker | `PreflightCheckId.BUILD_DIAGNOSTICS` | Reads this process's own environment and FAILs the readiness check, naming every shim that is on |
| Crash report to LGTM | `LabSessionRecovery` → `TelemetryEncoder.sessionInterrupted` | The next launch ships one `ERROR` log record per recovered session, so a kill is visible in Loki instead of appearing as a gauge that stopped |

The preflight check reads the **environment**, not the build config, and that is deliberate: the
scheme can be edited, an old build can be left on a device, and `isDebug()` is true for plenty of
runs that carry no shims at all. Only the running process can answer for the running process.

After changing the scheme, confirm it in Xcode under **Product > Scheme > Edit Scheme > Run**:
Diagnostics should show Main Thread Checker and Thread Performance Checker unchecked, and Options
should show GPU Frame Capture "Disabled" and Metal API Validation "Disabled". The scheme keys are
not documented by Apple, so read the UI rather than trusting the file.

## Protocol A — a fingerprinting walk

1. Run **Check** (pre-flight, judged as a walk). Grant anything red.
2. Start the walk. Hold the phone **up and forward** so the camera preview shows the room ahead —
   never the floor, never a blank wall. Wait for quality `NORMAL` before moving.
3. If the site has a saved map, stand still near where earlier walks have been until relocalisation
   completes (quality reads `NORMAL`); the console log says the map loaded.
4. Walk at a normal pace. Watch the coaching line, not the numbers.
5. Record **at least three waypoints** at printed `MONAD-FP` cards, spread across the walk —
   beginning, middle, end.
6. Stop. Read the close summary; a "MOSTLY UNTRUSTED" track is cheaper to re-take now.

## Protocol B — a probing (stationary) walk

The arm that resolves what a moving walk cannot: the CSI statistic against a *fixed* position, no
direction-of-motion confound. This is the arm that can settle the measured-vs-simulated sign
disagreement on the Fresnel statistic (ρ = −0.22 walked vs +0.63 simulated).

1. Start a walk session as above; get tracking `NORMAL`.
2. Go to a card. Select its number, press **Dwell on MONAD-FP-NN** — this records the waypoint and
   opens a `dwell_start`/`dwell_end` bracket.
3. Stand still on the card for the planned time (60 s default). The console shows the dwell timer.
4. Press **End dwell**. Move to the next card. Repeat.
5. A dwell left open at stop is closed automatically and marked as such.

Analysis-side: `walk_info` lists the dwell windows; the co-validation selects CSI windows inside
`dwell_start`…`dwell_end` and reads position from the card's surveyed location, not from odometry.

## Protocol C — a SURVEY walk (2026-08-26)

The walk that produces coordinates rather than consuming them. Run this once per room layout;
every later walk rides its transform.

**Why it exists.** A walk's session frame is metric, gravity-aligned and internally consistent —
the 2026-08-26 library walk measured 99.91 % `normal` tracking, zero rejected jumps and zero gaps
over one second across 235 m — but it is arbitrarily **placed**. The shape of the scanned points is
a measurement; only its position and heading on the floor are unknown, and those are a rigid
transform. **Two known points determine it.**

**What may be used as a known point.** Two things, and nothing else:

- **A node sticker.** `monad01` … `monad10` in the `exp: fiit-ground-fleet` layer of
  `fiit-ground-0` are **surveyed** (operator-confirmed 2026-08-26). Their QR carries
  `/d/<hostname>`, and the console reads that grammar as well as the cards' `/m/<slug>`.
- **A tape measurement you take in the room**, typed into the survey-anchor field.

**What may NOT.** Every `MONAD-FP` and door-card position in the same bundle layer
(`exp: fiit-ground-markers`) is fiction placed in QGIS. The proof is in the walk itself:
`OPEN-D1-OUT` and `SILENT-D1-IN` are the two sides of one doorway, the walk measures them 0.21 m
apart, and the bundle places them 20.46 m apart. Every IN/OUT pair in the bundle is exactly 0.60 m
apart, which is a template somebody typed.

### The protocol

1. Run **Check**. It now judges room geometry and live telemetry as well; grant anything red.
2. Start the walk. Perimeter slowly first, phone **up and forward**, wait for `NORMAL`. That is the
   technique that produced 99.91 % tracking — do not change it.
3. Dwell ~30 s at **all ten nodes**. Point the phone at the node sticker; the "seeing" row fills
   with `monad01`. Press **Dwell**. Scanning rather than typing matters here: the printed path
   (`/d/` vs `/m/`) is what records `target_kind = node`, and a typed code asserts no kind. A node
   dwell sits at zero distance from one end of every link that node terminates — the degenerate
   corner of the geometry — so pooling it with card dwells yields a statistic nobody can read.
4. Dwell ~30 s at **every card**, including the `MONAD-FP-09` … `FP-12` the first walk missed, and
   the IN/OUT pairs.
5. Optionally type a tape coordinate in the **surveyed site position** field before pressing Dwell
   at two cards far apart, one per room. Belt and braces: it makes the fit independent of the node
   survey entirely.
6. Keep it **one continuous session**. Two sessions cannot share a transform.
7. Stop. Read the close summary. Confirm the **anchored** count is at least 2 — the waypoint panel
   counts them separately for exactly this reason.
8. Check S3 for `mesh.ply` before leaving.

### The error term that actually bounds this

A dwell records **where the phone was**, not where the sticker is. Nodes sit at z = 90 cm, cards at
z = 140 cm, and you hold the phone near neither. That offset — 0.2 to 0.5 m — dominates every other
term in the chain.

**A consistent offset does not average out.** Ten anchors reduce a *random* offset by roughly √10;
standing on the same side of every sticker shifts the whole fit by the offset itself. So put the
phone at the sticker's horizontal position, ideally touching the wall or post below it, **the same
way every time**.

Card positions from this path are good to roughly 0.2–0.3 m, not centimetres. That is comfortably
inside what a position-labelled CSI feature for occupancy or counting needs, because those features
are room-scale. It is **not** inside what a claim about wavelength-scale geometry needs, and no
walk-derived position should be used for one.

### Then, in a fresh session

```
walk_survey(source="app:1/<session>", floor="fiit-ground-0",
            arrangement="<arrangement>", anchor_experiments="fiit-ground-fleet")
```

It solves the transform, reports a per-anchor residual and a scale ratio, validates the fit against
`mesh.ply`, places every other dwelled code, and prints the exact `gis_place` calls. **It writes
nothing** — you read every coordinate first, and the residual travels in `provenance` on each row.

It refuses rather than guesses. Fewer than two anchors, a scale ratio that is not 1.00, or an
anchor residual past 1 m each stop it before a coordinate exists.

## Analysis surfaces (monad-knowledge)

- `walk_sessions` / `walk_info` / `walk_plot` (`overview`, `trajectory`, `quality`, `speed`,
  `site`) — MCP tools over the uploaded artefacts.
- `walk_survey` — **the survey path.** Solves the transform from anchors the walk carries, so it
  needs neither ICP to converge nor a mesh at all. Prints the `gis_place` calls; writes nothing.
- `walk_register` — registers `mesh.ply` against a committed floor bundle (2-D trimmed ICP) and
  returns the session→site transform **with its RMS residual**. Quote the residual with any
  position it produces. Now reports whether the descent *converged* or merely hit its cap — those
  were the same reported state until 2026-08-26.

**The mesh is better as a VALIDATOR than as a solver.** Solve on the anchors, apply the transform
to the mesh, and measure how far its walls land from the plan's. Walls agree → the anchors, the
tracking and the bundle are mutually consistent. Walls disagree → something upstream is wrong, and
you know before writing positions rather than after. A solver that fails is silent about why; a
validator that fails names the disagreement.

The 2026-08-26 registration returned RMS 1.222 m at 23 % inliers and named none of five causes.
Fixed: **1.222 m / 23 % → 0.347 m / 66 %, converged.** The five were area-weighted sampling instead
of an index stride, `structures` as well as `walls` on the plan side (686 points for a 28 m building
was starving the target set), a 200-iteration cap instead of 30, a class filter that degrades rather
than empties — and the one nobody had listed: **the two frames have opposite handedness.** ARKit's
`(x, z)` reads left-handed from above while the site frame is right-handed, and the solver forbids a
fitted reflection on purpose, so the two constraints together made the fit unsatisfiable. The flip
now lives in `register` and `Registration.apply` only, so callers pass raw `(x, z)` and no call site
can be half-converted.

## The config row and the "Reload config" button

Renamed 2026-08-20. It used to say **Bundle**, which named the artefact it fetches rather than
the action it performs, and the row beside it read `bundle  v0 (cache)` — three pieces of jargon
in nine characters, none of which answered the question an operator has before walking out.

The button refetches `GET /api/lab/config`. The rows report what is loaded:

| Row reads | Means |
|---|---|
| `NOT LOADED — press Reload config` | Nothing was ever fetched. Every session runs against `LabConfig.EMPTY`: no site, no telemetry, no anchor plan. **A blocker.** |
| `cached — may be stale` | The last good copy from disk. Normal once the phone is on an AP with no route out, but only the server knows if it is current. |
| `from server (v3)` | Fetched this launch. The one unambiguous good state. |
| `set by hand` | Typed in on the bench. |
| `site: unset — walks cannot be placed on a floor` | No site slug, so a trajectory cannot be registered to a floor bundle afterwards. |
| `telemetry: NOT SHIPPING …` | The bundle carries no collector endpoint. Read from the **courier**, not from the config — on 2026-08-26 the bundle on the server was correct and the handset shipped zero lines for 21 minutes, so a row reporting the config cannot tell "configured" from "working". |
| `telemetry: N sample(s) in M flush(es)` | Actually shipping. `configured … nothing shipped yet` is the interesting middle state. |

`v0` is not an error — it means the backend never set a version. That is why the version is no
longer the headline.
