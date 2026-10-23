# Product page fields (App Store Connect, iOS App Version 1.2, English (U.S.))

Where each field on the version page comes from. The long fields are their own files, because
each is pasted into a plain text box: `promotional-text.md`, `description.md`, `keywords.md`,
`review-notes.md`. Lengths are checked against the limits App Store Connect shows.

| Field | Value | Source |
|---|---|---|
| Promotional Text (170) | paste `promotional-text.md` | header line of the creative asset |
| Description (4000) | paste `description.md` | onboarding copy, `review-notes.md` |
| Keywords (100) | paste `keywords.md` | comma-separated, no spaces |
| Support URL | `https://monad.dubec.dev/join` | returns 200 and shows the contact address (checked 2026-10-23) |
| Marketing URL | `https://monad.dubec.dev/` | optional; returns 200 (checked 2026-10-23) |
| Privacy Policy URL (App Privacy page) | `https://api.monad.dubec.dev/privacy-policy` | the page the app's onboarding links to (`OnboardingScreen.kt`); source `monad-backend/.../public/privacy-policy.html`; returns 200. NOT `monad.dubec.dev/privacy/`, which covers the library sensors and says the app is not public |
| Version | `1.2.0` | `TESTFLIGHT-BETA.md`: ASC shows `1.2`, the build is `1.2.0` |
| Copyright | **open** | who holds it (the researcher or FIIT STU) is a decision, not a fact in this repo |

There is no `/support/` page and `/terms/` returns 404 (checked 2026-10-23), so neither is used.

## App Review Information

| Field | Value |
|---|---|
| Sign-in required | yes |
| User name | the reviewer account from `TESTFLIGHT-BETA.md` 4.3 (`appreview@dubec.dev`) |
| Password | set when the account is created; never written in this repository |
| Contact first / last name | Jakub / Dubec |
| Contact email | `jakub.dubec@stuba.sk` (`AppConfig.SUPPORT_EMAIL`) |
| Contact phone | **open**: App Store Connect requires one; it is not in this repository |
| Notes (4000) | paste `review-notes.md` |

## Media

| Slot | Files |
|---|---|
| iPhone with Dynamic Island (medium display) | `asset-library/en-US/iPhone63_*.jpg`, 7 files, 1206 x 2622 |
| Header and Search Results (one universal asset) | `asset-library/creative/header-universal-5244x2950.png` |

Rebuild the screenshots with `python3 app-store/build_asset_library.py`. The header is drawn by
`creative/header.html`; its first comment holds the two render commands.
