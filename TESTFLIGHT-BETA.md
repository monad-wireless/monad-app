# TestFlight beta — what to configure and in what order

Written 2026-09-18, from the App Store Connect screenshot in the vault's `capture/` and from the
state of this repository on that date. Decisions taken with the researcher the same day:

| Decision    | Choice                                                            |
|-------------|-------------------------------------------------------------------|
| Signing     | Paid team. Push and Universal Links entitlements requested again. |
| Platforms   | iOS TestFlight only for round 1. Android signups wait.            |
| Upload path | Manual Xcode Archive to Organizer. No CI, no API key.             |
| Testers     | External group, invited by email from the onboarding desk.        |

`STUDENT-BETA-FORMS.md` holds the recruitment copy. This file holds the distribution mechanics.

---

## 1. What the screenshot shows, and what it does not

The screenshot is the **App Store** page for version 1.2, not the TestFlight page. Almost nothing
on it blocks a beta. Read the two columns apart, because filling the wrong one first costs a day.

| Empty on that page                            | Blocks TestFlight?            | Blocks a public release? |
|-----------------------------------------------|-------------------------------|--------------------------|
| Screenshots, 6.5" iPhone                      | no                            | yes                      |
| Description, keywords, promotional text       | no                            | yes                      |
| Support URL, marketing URL, copyright         | no                            | yes                      |
| Build section                                 | **yes**                       | yes                      |
| Sign-in credentials for the reviewer          | **yes, for external testers** | yes                      |
| App Privacy (Trust and Safety in the sidebar) | no                            | yes                      |

So the real blockers for a student beta are three: no build has ever been uploaded, no reviewer
account exists, and the TestFlight tab has no test information on it.

Two smaller things on that page are worth fixing while you are there.

**The version string does not match the build.** App Store Connect says `1.2`. This repository
says `1.2.0` (`gradle.properties: monad.version`, checked against Xcode by
`:composeApp:verifyIosAppVersion`). TestFlight does not care, because it groups builds by whatever
the build declares. App Store submission does care: you cannot attach a `1.2.0` build to a `1.2`
version. Edit the Version field in App Store Connect to `1.2.0`. Do not change the repository —
three version numbers already disagreed once and the sidecar recorded the one nobody agreed with.

**Confirm the record's bundle ID is `dev.dubec.monad`.** The app record is named "Monad Scanner"
and the app calls itself "Monad Scan". The names may differ, the bundle IDs may not.

---

## 2. Verify the Team ID first

Everything else depends on this and it is not yet checked.

`3D6D8LQ6F2` is the **free personal team**'s ID, and this repository has only ever known that one.
App Store Connect holding an app record proves a paid Apple Developer Program membership now
exists. It does not prove the membership kept the same Team ID.

Read the live value in Xcode, under Settings then Accounts, beside the membership row. If it
differs from `3D6D8LQ6F2`, change it in three places:

1. `DEVELOPMENT_TEAM` in `iosApp/iosApp.xcodeproj/project.pbxproj`.
2. The `CheckInWidget` target's copy of the same setting.
3. `web.applinks.ios_app_ids` in monad-knowledge's `config.toml`, as `<TEAMID>.dev.dubec.monad`.

A wrong value in the third place is the dangerous one. Apple's CDN caches the association file and
installed handsets re-check it about weekly, so a mismatch unbinds every printed marker for up to a
week and nothing reports an error.

---

## 3. Firebase

The project `monad-count` exists and the iOS app `dev.dubec.monad` is registered in it. Three
things still need doing, and one is a trap.

### 3.1 Keep the plist

`iosApp/iosApp/GoogleService-Info.plist` is present on this machine and is **gitignored**. The
archive build copies it in through the "Bundle GoogleService-Info.plist if present" phase. Lose the
file and the archive silently ships without Firebase: no crash reporting, no push, and no error.

Before you archive, confirm the file is there.

### 3.2 APNs key, for push

Push has never worked on iPhone. The entitlement was absent, so `FirebaseMessaging` had no APNs
token to register. The entitlement is now requested (`iosApp/iosApp/iosApp.entitlements`), which is
necessary and not sufficient. Firebase also needs a key to talk to Apple.

1. In the Apple Developer portal, open Certificates, Identifiers and Profiles, then Keys.
2. Create a key with the Apple Push Notifications service enabled.
3. When asked for the environment, choose **Sandbox and Production**. See below.
4. Download the `.p8`. Apple lets you download it once. Note the Key ID.
5. In the Firebase console open Project settings, then Cloud Messaging, then the Apple app.
6. Upload the `.p8` with its Key ID and the Team ID from section 2.

**Both environments, one key.** This is not the old certificate flow, where sandbox and production
were two separate certificates. A token-based `.p8` covers both, and Firebase takes exactly one of
them and picks the environment at send time from the app's `aps-environment` entitlement.

You need both because you will use both. A build run from Xcode over a cable is a development build
and talks to sandbox APNs. A TestFlight build is a distribution build and talks to production APNs,
because Xcode substitutes `production` for the `development` in the entitlements file when it
exports the archive. A sandbox-only key would work on your desk and deliver nothing to a student.

Apple allows two active APNs keys per account and the key does not expire. Keep the `.p8`
somewhere you will find it again, because the portal will not hand it over twice.

### 3.3 Service account, for the backend

The backend sends push through kreait and falls back to a null sender when
`MONAD_FCM_CREDENTIALS` does not name a file. Generate the credential in the Firebase console
under Project settings, then Service accounts, then Generate new private key.

Mount the **directory**, never the file. Docker creates a directory when a bind mount names a
missing file, and the backend's `is_file()` check then fails in a way that reads like a
permission problem.

### 3.4 Analytics is off, deliberately or not

`IS_ANALYTICS_ENABLED` is `false` in the plist. Crashlytics works without Analytics. What you lose
is breadcrumbs and velocity alerts, which are the parts that tell you a crash is spreading rather
than that it happened. Your call. Nothing else in the app depends on Analytics.

---

## 4. App Store Connect

### 4.1 Upload the first build

```
Xcode -> Product -> Destination -> Any iOS Device (arm64)
Xcode -> Product -> Archive
Organizer -> Distribute App -> App Store Connect -> Upload
```

Leave **Upload your app's symbols** checked. It is what makes a crash report readable, in Apple's
Organizer and in Crashlytics alike.

Export compliance is already answered: `ITSAppUsesNonExemptEncryption` is `false` in
`Info.plist`, so no prompt appears and no documentation is needed.

Two rules about numbers. Every upload needs a `CFBundleVersion` that is new within its version
train, so the second upload of `1.2.0` must not be build 5 again. A TestFlight build expires 90
days after upload.

**A rejected delivery burns its build number.** Build 6 of `1.2.0` was rejected at processing on
2026-09-19 and cannot be re-used. The repository is on build 7.

### 4.1.1 ITMS-90683, and why one of the three was refused

Build 6 came back with three missing purpose strings. Apple demands a string for a code
**reference**, not for a call that asks the user for anything, so all three were flagged even though
the app prompts for none of them.

| Key | Referenced by | Answer |
|---|---|---|
| `NSNearbyInteractionUsageDescription` | `NISession.isSupported()` in `HandsetDescriptor.ios.kt` and `SensorModules.ios.kt` | **Added.** Blocking. |
| `NSLocationWhenInUseUsageDescription` | `CLLocationManager.headingAvailable()` | Deliberately absent. |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | the same | Deliberately absent. |

The location pair was a warning, not a requirement, and the answer is no. iOS location was removed
on 2026-08-26 with the beacon witness, the only reference left is a static magnetometer check that
asks for nothing, and the consent copy a participant agrees to promises no location of any kind. A
purpose string claiming otherwise would have the app contradict its own consent text to silence a
warning Apple does not require fixing.

That is a decision with a cost attached. If Apple ever promotes those two from warning to error, the
choice is to add the strings **and** fix the consent copy, or to delete the `headingAvailable()`
probe and lose a real field from the handset descriptor. Restoring iOS beacon witnessing (Phase 5)
settles it the other way and brings the whole set back at once.

### 4.2 Test Information, on the TestFlight tab

External testing needs all of this before Beta App Review will look at the build.

| Field | Value |
|---|---|
| Beta app description | What the app is for and what a tester is expected to do. |
| Feedback email | `jakub.dubec@stuba.sk` — the same address the app, the site and `/join` show. |
| Privacy policy URL | `https://monad.dubec.dev/privacy/` |
| Sign-in required | Yes, with a working demo account. See below. |

### 4.3 The reviewer account, and the honest note beside it

The app requires a login, so App Review needs an account. Make one on the deployment:

```bash
docker exec -it monad_api php bin/console app:user:create appreview@dubec.dev
```

This is the part most likely to go wrong. **A reviewer cannot complete a quest.** The measurement
tasks scan printed markers in one building at FIIT STU in Bratislava, and there is nothing for a
phone in California to scan. An app that appears to do nothing is how a rejection for incomplete
information starts. Say so in the review notes, in plain terms: the app is a research instrument
for an on-site study, the quest list will be empty or unreachable away from the site, and here is
what the reviewer can see instead — login, onboarding, the inbox, the settings screen.

Two more things on that page invite questions you should be ready for.

- `NSLocalNetworkUsageDescription` says the app "sends UDP packets to devices on your local network
  for research data collection". That describes the collector, and there is no collector any more:
  `access_points` and `collector.host` in the lab bundle are empty because the AX210 cannot be an
  access point. The string now promises behaviour the build does not have. Either correct it or be
  ready to explain it.
- The app asks for Bluetooth always, camera and local network. Each purpose string is written and
  specific, which is the right shape. Keep the privacy policy consistent with them.

### 4.4 App Privacy

Not needed for a beta, needed before any public release, and the answers are the same either way.
Fill it while the facts are in front of you rather than on submission day. The app collects crash
data (Crashlytics, opt-in), diagnostics (the IP-133 handset gauges) and an account email. It
collects no advertising identifier, no precise location and no video.

---

## 5. Inviting the students

Two lists exist and they must not drift apart.

- `beta_signups` in the backend is the **record**: who asked, when, on what consent text, with what
  status. It is worked from `/admin/people/onboarding`.
- The TestFlight external group in App Store Connect is the **distribution list**. Apple sends its
  own email when you add an address to it.

The sequence for one student:

1. They submit `https://monad.dubec.dev/join`. A row appears with status new.
2. On the onboarding desk, download the Slovak invitation as `.eml`. Open it in Apple Mail, then
   Message and Send Again to edit and send. The download never changes the signup's status.
3. They reply that they want to take part.
4. Add their address to the TestFlight external group in App Store Connect. Apple emails them the
   TestFlight invitation.
5. Mark the signup invited on the desk.

Tell them one thing Apple does not: **the address must be the one their Apple ID uses**, or the
TestFlight invitation will not open. The signup form does not ask for it and it is the most common
way a student gets stuck at step 4.

The current invitation text already promises the install link as a second message
("Po potvrdení účasti ti pošlem odkaz na inštaláciu"), so no wording changes are needed for this
flow.

Beta App Review approves the first build of each version train, not each build. Expect a wait on
the first upload only.

Two open items, both the researcher's to settle, both named here rather than assumed: the 90-day
retention rule for signups is unconfirmed, and the consent text version `2026-09-16` in
`App\Join\JoinConsent` is still awaiting approval.

---

## 6. Telemetry, and how it lines up with App Store Connect

Three channels collect three different things. They are independent and none replaces another.

| Channel | Carries | Lands in | Turned on by |
|---|---|---|---|
| Apple crash collection | OS crash reports | Xcode Organizer, App Store Connect | the tester's own TestFlight sharing setting |
| Firebase Crashlytics | crashes and non-fatals | Firebase console | on by default, participant may opt out |
| IP-133 handset telemetry | instrument health gauges | Alloy, then Mimir | the lab bundle credential, while a session records |

They coexist. Apple's report is written by the operating system, Crashlytics writes its own from
its own handler, and the two counts will not match: Apple's depends on a setting inside TestFlight
that the tester controls, Crashlytics does not.

### 6.1 Crashlytics reported nothing after the first launch, until today

`App()` called `setCrashlyticsCollectionEnabled(false)` on every process start. The terms step in
onboarding called it with `true`, once, ever. Firebase persists that flag, so the sequence was:
first launch disables, onboarding enables, second launch disables again, and every crash from then
on went nowhere. The absence looked like a stable app.

Changed on 2026-09-18, and the consent model changed with it. Collection is **on by default** and
nothing gates it. The onboarding terms step no longer touches it. A participant can still turn it
off, and that opt-out (`SettingsRepository.KEY_CRASH_REPORTING_OPT_OUT`) is now the only thing that
does. Both platforms declare the default in the manifest and the plist, and `App.kt` re-asserts the
answer at every launch, which is what clears the persisted `false` the old build left behind.

Two consequences for you.

- Any handset that ran the old build needs one launch of the new build before it reports anything.
- The terms copy shown at onboarding should say that the app reports crashes, because the step no
  longer asks. That is a copy change and it is yours to write. The same sentence belongs in the App
  Privacy answers (section 4.4) and in the privacy policy.

### 6.2 The join to LGTM

Crashlytics and the LGTM stack see different halves of the same handset. Crashlytics has the stack
trace and no idea what the instrument was doing. Mimir has the instrument health and no idea why it
stopped. `core/telemetry/CrashContext.kt` joins them: every crash report carries `build_id` (the
string the session sidecar records) plus `site`, `platform` and `participant` — the three labels
Alloy's `handset_labels` transform keeps, spelled the same way, bound by `LabTelemetryShipper` at
the moment it learns them.

So a Crashlytics crash gives you a participant and a minute to query Mimir with, and a gap in a
Mimir series gives you something to ask Crashlytics about. `participant` is also set as the
Crashlytics user id, which makes the crash-free-users figure usable; it is an opaque pseudonym with
no foreign key to an account anywhere in this project.

### 6.3 What sending crashes INTO LGTM would take

Not done, and it is not a refactor. `LabTelemetryShipper` ships only while a session records, and a
crash ends the process before the next flush, so a crash signal on the wire needs four things:

1. A crash marker persisted at crash time and read on the next launch
   (`didCrashOnPreviousExecution` already gives the second half).
2. A new metric name in `TelemetryEncoder.METRIC_NAMES`, mirrored in Alloy's
   `filter "handset_allowlist"` — the mirror is the containment, because the OTLP credential travels
   to handsets and must be assumed public.
3. The shipper flushing **outside a session**, which is the real decision here: it changes what an
   idle handset on a student's phone does with its network.
4. An Alloy and control-plane deploy.

Item 3 is a privacy posture change, not a feature flag, so it is named here rather than assumed.

### 6.4 Symbolication

`isStatic = true` in `composeApp/build.gradle.kts`, so the Kotlin/Native code links into the app
binary rather than a separate framework. The app's own dSYM should therefore carry the Kotlin
frames, and both collectors should be able to read them. That is the expectation, **not a measured
fact** — nothing in this session symbolicated a real crash.

Settle it on the first TestFlight build: force a crash in shared Kotlin code, then check that the
stack in Xcode Organizer and the stack in Crashlytics both name Kotlin symbols rather than
addresses. If they name addresses, the Kotlin/Native release link is stripping debug information
and that is the next thing to fix.

### 6.5 What does not change

The handset OTLP telemetry ships straight to Alloy on port 4319 and never touches Apple or
Firebase. Store distribution changes nothing about it. The credential still arrives in the lab
bundle from `GET /api/lab/config`, so a tester who is not enrolled in a session ships nothing.

---

## 7. Order of work

1. Verify the Team ID (section 2). Everything depends on it.
2. Open the project in Xcode. Confirm Signing and Capabilities shows Push Notifications and
   Associated Domains without an error. The entitlements file requests both.
3. Create the APNs key and upload it to Firebase (section 3.2).
4. Set `web.applinks.ios_app_ids` correctly and release the site, so the association file stops
   serving an empty claim.
5. Archive and upload a build (section 4.1).
6. Create the reviewer account, write the review notes, fill Test Information (sections 4.2, 4.3).
7. Submit for Beta App Review, create the external group.
8. Work the onboarding desk (section 5).
9. On the first installed build, force a crash and check both stacks (section 6.4).
