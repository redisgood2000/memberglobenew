# Member Globe group chat and push notifications

## What's added
- Native map overlay group chat using Firestore's `groupMessages` collection.
- Live message updates for approved members.
- Firestore rules restricting chat reads and sends to approved members.
- Android Firebase Cloud Messaging token registration and notification handling.
- Optional Cloud Function that sends a push notification to other approved members when a message is posted.

## 1. Publish Firestore rules
In Firebase Console → Firestore Database → Rules, publish the updated rules from `firestore.rules`. The existing member/location rules are retained.

## 2. Build the Android APK
The GitHub Actions workflow should build the native Android app from the `firebase-migration` branch. Install the newest successful APK and allow notifications when Android asks. On Android 13+, push notifications won't display unless notification permission is granted.

## 3. Enable actual background push delivery (optional backend)
The app can show incoming FCM notifications, but a trusted server must send them. The included sender is in `functions/index.js`.

Deploying Firebase Cloud Functions requires the Firebase **Blaze billing plan** and a linked billing account. The function may fall within available no-cost quotas, but usage beyond them can incur charges. Do not upgrade if you want to keep the project strictly free; in that case, group chat still works live while the app is open, but automatic notifications when the app is closed will not be sent by this project.

If you choose to enable it:
1. Install Node.js 20 and the Firebase CLI on a computer.
2. From the repository root, run `firebase login`.
3. Confirm the CLI points to project `member-globe-91998` with `firebase use member-globe-91998`.
4. Run `firebase deploy --only firestore:rules,functions`.
5. Confirm the function `notifyGroupChat` is deployed in region `asia-southeast1`.

## Notes
- This feature does not change GPS sharing consent or the member approval flow.
- The sender does not receive a push notification for their own message.
- Notifications are sent only to devices whose member profile is currently approved.
- Never put service-account private keys or admin credentials in the Android app or repository.
