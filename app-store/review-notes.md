Monad Scanner (home screen name "Monad Scan") is a research instrument for a doctoral study at FIIT STU (Faculty of Informatics and Information Technologies, Slovak University of Technology in Bratislava). The study tests whether sensors can count people in a room from Wi-Fi and Bluetooth signals, without a camera. It is distributed only to student volunteers. No purchases, no advertising, no user-generated content.

ACCESS IS INVITATION ONLY
Please use the demo account in the Sign-In Information fields. Registration works, but an uninvited email is created as pending and cannot sign in until the researcher approves it, so a reviewer's own registration ends on the "Waiting for approval" screen. This is intended.

QUESTS AND CHECK-IN NEED THE BUILDING
The home screen lists the study's quests and a "Check in" card. You can open every quest and you are welcome to start any of them. Each quest step and every check-in asks for a scan of a printed QR card fixed in one building at FIIT STU in Bratislava. Away from it there is no card to scan, so a started quest waits at its first scan step and a check-in cannot begin. This is expected. The researcher removes anything the demo account records from the study data.
On site, a check-in shows a Live Activity with the place, the elapsed time and a Check out button.

WHAT THE REVIEWER CAN TEST
- Sign-in with the demo account, and registration (ends on "Waiting for approval").
- Onboarding: study information, permissions, terms.
- Quest list and quest details. Starting a quest is allowed.
- Notification inbox and notification settings.
- My Account: points, coverage map, password change, account deletion. Deletion removes the account on the server and signs out. Please do not delete the demo account.
- "Forgot Password?" opens a dialog with the researcher's email. Recovery is manual: the researcher sets a temporary password, the participant then changes it in My Account.

PERMISSIONS
- Bluetooth: during a quest or check-in the phone broadcasts an anonymous identity frame and records Bluetooth advertisements, so the study's sensors can match the phone to a place and time.
- Background Bluetooth: a check-in lasts while the participant stays, with the phone locked, so the identity frame must stay on air in the background. Nothing is broadcast when no quest or check-in runs.
- Camera: reads the QR cards. On a measurement walk it also tracks the phone's own movement. No photos or video are saved or uploaded.
- Local network: the purpose string describes timing packets to a lab collector. The current lab configuration enables no collector, so this build sends none.
- Nearby Interaction: the app only checks whether the phone supports ultra-wideband, for the device description. It does not range to other devices.
- Notifications: study announcements via Firebase Cloud Messaging, also shown in the in-app inbox.
The app does not request location.

DATA
Account: email and optional name. Uploaded to the study's server: QR scans, check-ins, Bluetooth records, step timing, typed-in headcounts, and at quest start a phone description (model, iOS version, sensors). On a measurement walk: the phone's own movement and, with LiDAR, a 3D outline of the room's surfaces (no images). A push token if notifications are allowed. While a measurement session runs, the app sends health metrics of that recording to the study's monitoring server. Crash reports go to Firebase Crashlytics, as the onboarding says. No advertising identifier, location, audio or video.

ENCRYPTION
ITSAppUsesNonExemptEncryption is false. Standard HTTPS only.

CONTACT
Jakub Dubec, FIIT STU, jakub.dubec@stuba.sk
