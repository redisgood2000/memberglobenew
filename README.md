# Member Globe

This repository is being migrated from AppDeploy to Firebase on the `firebase-migration` branch.

## Migration status

- `web/`: Firebase Google sign-in, pending/approved membership flow, profile editing, admin review, opt-in GPS sharing, and a live global map.
- `native-android/`: Firebase Google sign-in, membership status, one-time GPS sharing controls, and a link to the web dashboard.
- `firestore.rules`: security rules for member profiles, admin review, and approved/consented location sharing.
- `firebase.json`: Hosting and rules deployment configuration.

**The app is not connected to a real Firebase project yet.** The client configuration intentionally contains empty values until the owner creates and configures a Firebase project. Follow [FIREBASE_SETUP.md](FIREBASE_SETUP.md) before deployment. Do not commit service-account credentials.

The existing `native-android` branch remains unchanged as a rollback point. The migration is isolated on `firebase-migration` so it can be reviewed and tested before merging.
