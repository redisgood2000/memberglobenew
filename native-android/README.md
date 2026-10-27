# Member Globe — Firebase Android app

This Kotlin + Jetpack Compose app is being migrated from AppDeploy to Firebase Authentication and Cloud Firestore.

## Current native features

- Google sign-in using Firebase Authentication (requires your Firebase project configuration).
- Creates a pending member profile after first sign-in.
- Displays the membership approval status from Firestore.
- Approved members can opt in to share a one-time current GPS location, refresh it manually, or stop sharing and remove the stored point.
- Opens the Firebase-hosted web dashboard for profile editing, admin review, and the live global map.

## Configure before signing in

Follow the root [FIREBASE_SETUP.md](../FIREBASE_SETUP.md). Add your own values to the untracked `local.properties` file in this directory:

```properties
firebaseApiKey=YOUR_FIREBASE_WEB_API_KEY
firebaseApplicationId=YOUR_FIREBASE_ANDROID_APP_ID
firebaseProjectId=YOUR_FIREBASE_PROJECT_ID
firebaseWebClientId=YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID
```

These must be copied from your Firebase/Google Cloud project. The web OAuth client ID is used as the Google Sign-In server client ID. Register package `com.memberglobe.nativeapp.test` and the exact signing certificate SHA-1 in Firebase/Google Cloud. Do not commit service-account keys.

## Build

Open this directory in Android Studio and build the debug APK, or use the GitHub Actions workflow on branch `firebase-migration`. The workflow can build in setup mode without project values; Google sign-in remains disabled until real config is supplied.
