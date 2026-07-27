# monad-app

The MonadCount **participant instrument**: a Kotlin Multiplatform (Compose) app that turns a phone
into part of the measurement apparatus for the lab's wireless-sensing experiments.

A phone here plays three roles at once — **illuminator** (emits a paced UDP stream from a station
associated to an experiment AP, giving the fleet's CSI receivers a uniform sounding source),
**witness** (observes surveyed iBeacon anchors and reports zone transitions), and **subject** (a
body in the room). It never captures CSI: no mobile OS exposes it.

Two constraints shape the whole design — **iOS is the first-class target**, and **the app is never
in the foreground during a session**. See `ARCHITECTURE.md`.

## Build

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # iOS
./gradlew :composeApp:assembleDebug                    # Android (minSdk 29)
open iosApp/iosApp.xcodeproj                           # iOS app (Firebase via SPM, resolved by Xcode)
```

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
