Monad Scan is a research instrument for a doctoral study at the Faculty of Informatics and Information Technologies, Slovak University of Technology in Bratislava (FIIT STU). It is distributed only to student volunteers who signed up for the study. It has no purchases, no advertising and no user-generated content.

ACCESS IS INVITATION ONLY
Please use the demo account supplied in the Sign-In Information fields. Registration in the app works, but an account whose email the researcher did not invite is created as "waiting for approval" and cannot sign in until the researcher approves it. A new registration by the reviewer therefore shows the "Waiting for approval" screen. This is intended.

WHY THE QUEST LIST IS EMPTY FOR THE REVIEWER
The main function, quests, runs only on site in one building at FIIT STU. Each quest step asks the participant to scan a printed QR marker that is fixed to a wall or a door in that building, or to stand near a sensor node there. Away from the building there is nothing to scan, so the quest list is empty or a quest cannot start. This is expected and is not a defect.

WHAT THE REVIEWER CAN TEST
- Sign-in with the demo account.
- Registration, which ends on the "Waiting for approval" screen for an uninvited email.
- Onboarding (study information and terms).
- The notification inbox.
- Notification settings.
- My Account: change password, and in-app account deletion. Deletion removes the account on the server and signs the user out. Please do not delete the demo account, or later reviews will have no account.
- "Forgot Password?" on the sign-in screen. It opens a dialog with the researcher's email address. Password recovery is manual for this closed study: the researcher sets a temporary password after the participant writes from the registered address, and the participant then changes it in My Account.

PERMISSIONS AND WHY EACH ONE EXISTS
- Bluetooth: during a quest the phone broadcasts a Bluetooth identity frame and records Bluetooth advertisements, so that the study's sensors can match the phone to a place and time.
- Camera: reading the printed QR markers. During a measurement walk the camera also tracks the phone's own movement through the room. No photos or video are saved or uploaded.
- Local network: the purpose string describes timing packets to a lab data collector on the local network. The current lab configuration does not enable a collector, so this build sends no such packets.
- Notifications: study announcements, through Firebase Cloud Messaging.
The app does not request location.

DATA
The account holds an email address and an optional name. Quest data (QR scans, Bluetooth records, step timing) uploads to the study's own server. Crash reports go to Firebase Crashlytics, as the onboarding tells the user. The app collects no advertising identifier, no location and no video.

ENCRYPTION
ITSAppUsesNonExemptEncryption is false. The app uses only standard HTTPS.

CONTACT
Jakub Dubec, FIIT STU, jakub.dubec@stuba.sk
