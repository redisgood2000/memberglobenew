# Group chat and push notifications

The Android app uses the Firestore `groupMessages` collection for a shared group chat. Only approved members can read or send messages. The app stores each signed-in user's current FCM token in `deviceTokens/{uid}`.

## Install and deploy the notification sender

1. In Firebase Console, open project `member-globe-91998` and ensure Cloud Messaging is enabled.
2. Cloud Functions deployment requires the Firebase **Blaze (pay-as-you-go)** plan. The Android chat itself uses Firestore, but automatic push notifications while the app is backgrounded or closed need a trusted server sender; do not send FCM messages directly from the Android client.
3. Install Node.js 20 and the Firebase CLI, then from the repository root run:
   ```sh
   npm install -g firebase-tools
   firebase login
   firebase use member-globe-91998
   cd functions
   npm install
   cd ..
   firebase deploy --only functions:notifyGroupChat,firestore:rules
   ```
4. Install the newly built APK, sign in as an approved member, and allow notifications when Android asks. Each approved member needs to sign in on their device at least once so their FCM token is registered.
5. Send a test message from one account. Other approved members should receive a high-priority notification; the sender is excluded from their own push.

The function targets approved members only and never sends a notification to the message author. Keep Firestore rules published from `firestore.rules`; do not open chat or token collections to unauthenticated users.
