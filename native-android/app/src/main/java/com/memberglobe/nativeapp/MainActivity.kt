package com.memberglobe.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : ComponentActivity() {
    var auth: FirebaseAuth? = null
        private set
    var firestore: FirebaseFirestore? = null
        private set
    var member by mutableStateOf<Map<String, Any?>?>(null)
        private set
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var memberListener: ListenerRegistration? = null
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeFirebase()
        setContent { MemberGlobeNativeScreen(this) }
    }

    private fun initializeFirebase() {
        val valuesPresent = BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
        if (!valuesPresent) {
            message = "Firebase is not configured yet. Follow FIREBASE_SETUP.md and add your own values to native-android/local.properties."
            return
        }
        try {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build()
            val app = FirebaseApp.initializeApp(this, options)
                ?: FirebaseApp.getInstance()
            auth = FirebaseAuth.getInstance(app)
            firestore = FirebaseFirestore.getInstance(app)
            auth?.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                memberListener?.remove()
                memberListener = null
                member = null
                if (user != null) ensureMemberProfile(user.uid)
            }
        } catch (e: Exception) {
            message = "Firebase initialization failed: ${e.message ?: "check your project config"}"
        }
    }

    fun startGoogleSignIn() {
        if (auth == null || BuildConfig.FIREBASE_WEB_CLIENT_ID.isBlank()) {
            message = "Firebase setup is incomplete. Add firebaseWebClientId from your Google OAuth Web client to local.properties."
            return
        }
        try {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.FIREBASE_WEB_CLIENT_ID)
                .requestEmail()
                .build()
            busy = true
            message = "Opening Google sign-in…"
            startActivityForResult(GoogleSignIn.getClient(this, options).signInIntent, REQUEST_GOOGLE_SIGN_IN)
        } catch (e: Exception) {
            busy = false
            message = "Could not start Google sign-in: ${e.message}"
        }
    }

    @Deprecated("Uses the Google Sign-In activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_GOOGLE_SIGN_IN) return
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                busy = false
                message = "Google did not return an ID token. Verify the Web OAuth client ID and Android SHA-1 in Firebase."
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth?.signInWithCredential(credential)
                ?.addOnCompleteListener { task ->
                    busy = false
                    if (task.isSuccessful) message = "Signed in securely with Firebase."
                    else message = "Firebase sign-in failed: ${task.exception?.message ?: "unknown error"}"
                } ?: run {
                    busy = false
                    message = "Firebase Auth is not initialized."
                }
        } catch (e: ApiException) {
            busy = false
            message = "Google sign-in failed (code ${e.statusCode}). Check OAuth configuration and SHA-1."
        } catch (e: Exception) {
            busy = false
            message = "Sign-in failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun ensureMemberProfile(uid: String) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val ref = db.collection("members").document(uid)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                val profile = hashMapOf<String, Any?>(
                    "uid" to uid,
                    "displayName" to (user.displayName ?: user.email?.substringBefore("@") ?: "Member"),
                    "email" to (user.email ?: ""),
                    "photoURL" to (user.photoUrl?.toString() ?: ""),
                    "phone" to "",
                    "bio" to "",
                    "status" to "pending",
                    "locationSharing" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(profile).addOnFailureListener { e ->
                    message = "Could not create member request: ${e.message}"
                }
            }
            memberListener = ref.addSnapshotListener { doc, error ->
                if (error != null) {
                    message = "Could not load membership status: ${error.message}"
                    return@addSnapshotListener
                }
                member = doc?.let { snapshotToMap(it) }
            }
        }.addOnFailureListener { e ->
            message = "Could not load member profile: ${e.message}. Check Firestore rules."
        }
    }

    private fun snapshotToMap(snapshot: DocumentSnapshot): Map<String, Any?> =
        snapshot.data ?: emptyMap()

    fun signOutMember() {
        memberListener?.remove()
        memberListener = null
        member = null
        auth?.signOut()
        GoogleSignIn.getClient(this, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build())
            .signOut()
        message = "Signed out."
    }

    fun shareCurrentLocation() {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        if (member?.get("status") != "approved") {
            message = "Only approved members can share location."
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            message = "Allow location permission first."
            return
        }
        busy = true
        db.collection("members").document(user.uid).update(
            mapOf("locationSharing" to true, "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            locationClient.getCurrentLocation(
                if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location: Location? ->
                if (location == null) {
                    db.collection("members").document(user.uid).update("locationSharing", false)
                    busy = false
                    message = "Could not get a GPS fix. Move outdoors and try again."
                    return@addOnSuccessListener
                }
                val point = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "lat" to location.latitude,
                    "lng" to location.longitude,
                    "accuracy" to location.accuracy,
                    "sharing" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("locations").document(user.uid).set(point)
                    .addOnSuccessListener {
                        busy = false
                        message = "Location shared. Press Update location again to refresh it."
                    }.addOnFailureListener { e ->
                        db.collection("members").document(user.uid).update("locationSharing", false)
                        busy = false
                        message = "Could not save location: ${e.message}"
                    }
            }.addOnFailureListener { e ->
                db.collection("members").document(user.uid).update("locationSharing", false)
                busy = false
                message = "Could not read GPS: ${e.message}"
            }
        }.addOnFailureListener { e ->
            busy = false
            message = "Could not enable sharing: ${e.message}"
        }
    }

    fun stopLocationSharing() {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        busy = true
        db.collection("members").document(user.uid).update(
            mapOf("locationSharing" to false, "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            db.collection("locations").document(user.uid).delete()
                .addOnCompleteListener {
                    busy = false
                    message = "Location sharing is off and your shared point was removed."
                }
        }.addOnFailureListener { e ->
            busy = false
            message = "Could not stop sharing: ${e.message}"
        }
    }

    companion object { private const val REQUEST_GOOGLE_SIGN_IN = 7341 }
}

@Composable
private fun MemberGlobeNativeScreen(activity: MainActivity) {
    val user = activity.auth?.currentUser
    val member = activity.member
    val context = LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            activity.shareCurrentLocation()
        } else {
            activity.message = "Location permission was denied. Your location has not been shared."
        }
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Member Globe") }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                Icon(Icons.Default.Public, contentDescription = null,
                    modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Member Globe", style = MaterialTheme.typography.headlineMedium)
                if (user == null) {
                    Text("Sign in with Google. New accounts wait for admin approval before member features are available.")
                    Button(
                        onClick = { activity.startGoogleSignIn() },
                        enabled = !activity.busy && activity.auth != null &&
                            BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (activity.busy) "Please wait…" else "Continue with Google") }
                    if (activity.auth == null) {
                        Text("Add your Firebase values to native-android/local.properties to enable sign-in.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(user.displayName ?: user.email ?: "Signed in")
                    val status = member?.get("status")?.toString() ?: "loading"
                    when (status) {
                        "pending" -> {
                            Text("Your membership request is pending admin approval.")
                            Text("After approval, use the web dashboard for the admin review page and live global map.")
                        }
                        "approved" -> {
                            Text("Membership approved.")
                            Text("Location sharing is ${if (member?.get("locationSharing") == true) "ON" else "OFF"}. GPS sharing is opt-in.")
                            Button(
                                onClick = {
                                    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (fine || coarse) activity.shareCurrentLocation()
                                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                },
                                enabled = !activity.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Update and share current GPS location") }
                            OutlinedButton(
                                onClick = { activity.stopLocationSharing() },
                                enabled = !activity.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Stop sharing and remove location") }
                            Text("This button shares one current GPS fix at a time. Tap Update to refresh your point; it does not track in the background.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Text("Membership is not active. Contact your administrator.")
                    }
                    OutlinedButton(onClick = { activity.signOutMember() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out")
                    }
                }
                if (activity.message.isNotBlank()) {
                    Text(activity.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                Text("Your location stays off until you choose to share it.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
