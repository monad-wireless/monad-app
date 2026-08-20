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

## Analysis surfaces (monad-knowledge)

- `walk_sessions` / `walk_info` / `walk_plot` (`overview`, `trajectory`, `quality`, `speed`,
  `site`) — MCP tools over the uploaded artefacts.
- `walk_register` — registers `mesh.ply` against a committed floor bundle (2-D trimmed ICP) and
  returns the session→site transform **with its RMS residual**. Quote the residual with any
  position it produces.

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
| `telemetry: live` / `off` | Whether this walk is visible on dashboard `39 · Handset Instrument` while it runs, or invisible until it uploads. |

`v0` is not an error — it means the backend never set a version. That is why the version is no
longer the headline.
