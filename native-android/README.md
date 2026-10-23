# Member Globe — Native Android starter

This directory is a Kotlin + Jetpack Compose Android starter on a separate branch.

## Included
- Native Android Studio/Gradle project
- Compose sign-in preview and dashboard
- Global member map, group chat, account, and admin-review placeholder screens
- Android internet, location, and notification permission declarations

## Not production-connected yet
The sign-in button is preview-only. The app does not yet authenticate against the live AppDeploy backend, retrieve real member GPS locations, send chat messages, approve registrations, or receive background push notifications.

Before production integration, inspect and verify the existing backend's exact auth/session/token flow, API routes, authorization rules, location-sharing consent, and notification setup. Do not put server secrets in the Android app. The existing website's browser session cannot be assumed to work in a native app.

## Build
Open this directory in Android Studio, sync Gradle, then choose Build > Build APK(s). A GitHub Actions workflow is also included to compile the project and upload a debug APK artifact.
