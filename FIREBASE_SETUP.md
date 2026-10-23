# Member Globe: AppDeploy to Firebase migration

This branch starts a Firebase-native replacement for the web dashboard and Android login. AppDeploy has not been removed from the historical branch, and this migration is not production-ready until you configure and test your own Firebase project.

## 1. Create your Firebase project

1. Open https://console.firebase.google.com/ and create a project you control.
2. Add a **Web app** in Project settings. Copy its web config into `web/firebase-config.js`. Keep the keys as values from your own project; do not invent them.
3. In **Authentication → Sign-in method**, enable **Google** and set the support email.
4. In **Authentication → Settings → Authorized domains**, add your deployed hosting domain.
5. Create **Firestore Database** in production mode. Do not use public/test rules.
6. In **Build → Realtime Database**, no instance is required for this first stage; the map uses Firestore listeners and the included Realtime Database rules deny access by default.
7. In Project settings, register the Android app with package name `com.memberglobe.nativeapp.test`. Download `google-services.json` only if following the Android Google Services setup; never commit private service-account credentials. This current native starter uses explicit local Gradle properties and FirebaseOptions instead, so follow the native config section below.

## 2. Publish security rules before using real member data

From the repository root, install the Firebase CLI and log in with your own account:

```sh
npm install -g firebase-tools
firebase login
firebase use --add
firebase deploy --only firestore:rules,firestore:indexes,database
```

Choose your own Firebase project when prompted. Review `firestore.rules` before deploying. `database.rules.json` intentionally denies all access because this version stores live map points in Firestore.

## 3. Create the first admin safely

The app deliberately cannot grant admin privileges to itself. After your own account signs in once and creates `members/{YOUR_UID}`, open Firestore Console and create a document:

- Collection: `admins`
- Document ID: your exact Firebase Authentication UID
- Fields: `role` (string) = `admin`, `createdAt` (timestamp, optional)

Never use an email address as the admin document ID; use the UID shown in Firebase Authentication. Only trusted project owners should create documents in `admins`. Remove this document to revoke admin access.

## 4. Host the web app

The repository contains a static web app in `web/`. After adding your config, run:

```sh
firebase deploy --only hosting
```

Google sign-in requires HTTPS (Firebase Hosting supplies it) or localhost. Do not use a plain LAN HTTP URL for production authentication or GPS.

## 5. Native Android Firebase config

The native app must use the same Firebase project and the **Web OAuth client ID** generated for Google sign-in as its server client ID. Register Android package `com.memberglobe.nativeapp.test` and the SHA-1 of the exact APK signing certificate in Firebase/Google Cloud. For local development, put project config in your untracked `native-android/local.properties` file (do not commit it):

```properties
firebaseApiKey=YOUR_VALUE_FROM_FIREBASE
firebaseApplicationId=YOUR_ANDROID_APP_ID_FROM_FIREBASE
firebaseProjectId=YOUR_FIREBASE_PROJECT_ID
firebaseWebClientId=YOUR_WEB_OAUTH_CLIENT_ID.apps.googleusercontent.com
```

Use values copied from Firebase/Google Cloud. Do not use a service-account key here. GitHub Actions will need repository Actions secrets or variables to build a configured native APK; no secrets are configured by this migration.

## 6. Member workflow and location privacy

- Google sign-in creates a member profile with `status: pending` and `locationSharing: false`.
- A trusted admin approves or rejects the request in the dashboard.
- Only approved members can opt in to GPS sharing.
- GPS is off by default. When a member turns it off, the browser stops watching GPS and attempts to delete that member's shared location.
- Approved member profiles are visible to approved members. A location record is readable only when sharing is enabled and the viewer is approved, or the viewer is an admin.
- Do not enable background tracking or collect GPS without clear, ongoing consent.

## 7. Existing AppDeploy users

Firebase Auth users are separate from AppDeploy users. This migration does not automatically copy passwords, AppDeploy sessions, member records, or historical GPS data. Have members sign in with Google and request approval again, or plan a reviewed export/import after confirming ownership and consent. Never migrate passwords or browser session tokens.

## Current staged status

- Added Firebase Hosting config, Firestore rules/indexes, and a Firebase-powered static web dashboard.
- Added Google sign-in, pending approval, member profile editing, admin approval, and consent-based live map on web.
- Native Android Firebase sign-in is the next implementation stage and must be tested with your project's real web client ID, Firebase Android app ID, and signing certificate before it can be considered connected.
- No Firebase project credentials were available during this repository edit, so the web app intentionally stays in setup mode until you configure it.
