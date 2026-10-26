# Android beta — the student APK

iOS goes through TestFlight (`TESTFLIGHT-BETA.md`). Android goes through one link that never changes:

    https://github.com/monad-wireless/monad-app/releases/download/android-latest/monad-app.apk

The workflow `.github/workflows/android-beta.yml` rebuilds it on every push to `feature/refactor`
(or by hand: Actions → Android beta → Run workflow, on that branch). It deletes and recreates the
`android-latest` pre-release, so the link always serves the newest build. The release notes carry
the `BUILD_ID`, which is the same string a session sidecar records.

No Google Play account is involved. The APK is installed directly from the link ("sideloaded"), and
the signing key is a file generated locally with `keytool`.

## Why a release key

Android installs an update over an existing app only when both are signed with the same key. A
debug build is signed with a key each machine makes for itself, so every CI runner would produce a
different signature, and each update would force an uninstall that deletes unsent sessions. One
keystore in the repository secrets makes the link an update channel.

> **Warning — keep the keystore.** Store `monad-release.jks` and its passwords in 1Password. If the
> keystore is lost, no later APK can update an installed app: every student must uninstall (losing
> local data) and install again.

## One-time setup

1. Generate the keystore (once, ever):

       keytool -genkeypair -keystore monad-release.jks -alias monad -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Monad, O=FIIT STU"

2. Store it as base64 (BSD `base64` on macOS needs `-i`):

       base64 -i monad-release.jks | gh secret set MONAD_KEYSTORE_B64 -R monad-wireless/monad-app
       gh secret set MONAD_KEYSTORE_PASSWORD -R monad-wireless/monad-app
       gh secret set MONAD_KEY_ALIAS -R monad-wireless/monad-app --body monad
       gh secret set MONAD_KEY_PASSWORD -R monad-wireless/monad-app

3. Optional, for Crashlytics and push on student phones:

       base64 -i composeApp/google-services.json | gh secret set MONAD_GOOGLE_SERVICES_B64 -R monad-wireless/monad-app

   Without it the workflow builds anyway and prints a warning.

## For students

Open the link on the phone, download `monad-app.apk`, allow the browser to install unknown apps
when Android asks, then tap Install. Android 10 (API 29) or newer. A later APK from the same link
installs over the old one and keeps the app's data.

## Versions

`monad.versionCode` in `gradle.properties` is not bumped by CI. An APK with the same versionCode
installs over itself. An APK with a lower versionCode is refused as a downgrade. Bump it when the
app's "Build" number should show students that something changed.

## Local release build

    MONAD_KEYSTORE_PATH=/path/monad-release.jks MONAD_KEYSTORE_PASSWORD=… MONAD_KEY_ALIAS=monad MONAD_KEY_PASSWORD=… \
      ./gradlew :composeApp:assembleRelease

Without `MONAD_KEYSTORE_PATH` the build succeeds and the APK is `composeApp-release-unsigned.apk`.
