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
keystore makes the link an update channel.

> **Warning — keep the keystore.** If it is lost, no later APK can update an installed app: every
> student must uninstall (losing local data) and install again.

## Where the secrets live

1Password is the source of truth, vault `MONAD Access`:

| Item | Kind | Holds |
|---|---|---|
| `android.monad-app.release` | Password | keystore password (PKCS12: store and key share it), field `alias` |
| `android.monad-app.keystore` | Document | `monad-release.jks` |
| `android.monad-app.google-services` | Document | `google-services.json` (Firebase) |

GitHub holds copies on the environment `android-beta`, whose deployment branch policy admits only
`feature/refactor`. No repository-level secret exists. Secrets: `MONAD_KEYSTORE_B64`,
`MONAD_KEYSTORE_PASSWORD`, `MONAD_GOOGLE_SERVICES_B64` (optional; without it the APK has no
Crashlytics or push and the run prints a warning). Variable: `MONAD_KEY_ALIAS`.

To rotate or re-sync a GitHub copy, pipe the value from 1Password straight into `gh secret set
--env android-beta`, as in the setup below. Nothing is written to disk.

## One-time setup

    # 1. Password item, generated inside 1Password (output discarded so the value is never printed)
    op item create --vault "MONAD Access" --category password --title android.monad-app.release \
      --generate-password='letters,digits,32' 'alias[text]=monad' > /dev/null

    # 2. Keystore in a private temp dir, password read from 1Password
    mkdir -m 700 /tmp/monad-ks
    export MONAD_KS_PASS="$(op read -n 'op://MONAD Access/android.monad-app.release/password')"
    keytool -genkeypair -keystore /tmp/monad-ks/monad-release.jks -storetype PKCS12 -alias monad \
      -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Monad, O=FIIT STU, C=SK" -storepass:env MONAD_KS_PASS

    # 3. Into 1Password, check the copy is byte-identical, then delete the local file
    op document create /tmp/monad-ks/monad-release.jks --vault "MONAD Access" --title android.monad-app.keystore > /dev/null
    op document get android.monad-app.keystore --vault "MONAD Access" | cmp - /tmp/monad-ks/monad-release.jks
    rm -rf /tmp/monad-ks
    unset MONAD_KS_PASS

    # 4. Firebase config into 1Password
    op document create composeApp/google-services.json --vault "MONAD Access" --title android.monad-app.google-services > /dev/null

    # 5. GitHub environment, restricted to feature/refactor
    gh api -X PUT repos/monad-wireless/monad-app/environments/android-beta \
      -F 'deployment_branch_policy[protected_branches]=false' -F 'deployment_branch_policy[custom_branch_policies]=true'
    gh api -X POST repos/monad-wireless/monad-app/environments/android-beta/deployment-branch-policies \
      -f name=feature/refactor -f type=branch

    # 6. Environment secrets and variable, streamed from 1Password
    op document get android.monad-app.keystore --vault "MONAD Access" | base64 \
      | gh secret set MONAD_KEYSTORE_B64 --env android-beta -R monad-wireless/monad-app
    op read -n 'op://MONAD Access/android.monad-app.release/password' \
      | gh secret set MONAD_KEYSTORE_PASSWORD --env android-beta -R monad-wireless/monad-app
    op document get android.monad-app.google-services --vault "MONAD Access" | base64 \
      | gh secret set MONAD_GOOGLE_SERVICES_B64 --env android-beta -R monad-wireless/monad-app
    gh variable set MONAD_KEY_ALIAS --env android-beta -R monad-wireless/monad-app --body monad

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

`MONAD_KEY_PASSWORD` equals `MONAD_KEYSTORE_PASSWORD` for this PKCS12 keystore.

Without `MONAD_KEYSTORE_PATH` the build succeeds and the APK is `composeApp-release-unsigned.apk`.
