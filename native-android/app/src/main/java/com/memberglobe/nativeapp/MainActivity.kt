package com.memberglobe.nativeapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
    var auth: FirebaseAuth? = null
        private set
    var firestore: FirebaseFirestore? = null
        private set
    var member by mutableStateOf<Map<String, Any?>?>(null)
        private set
    var sharedLocations by mutableStateOf<List<Map<String, Any?>>>(emptyList())
        private set
    var approvedMembers by mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap())
        private set
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var memberListener: ListenerRegistration? = null
    private var locationsListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var mapListenersUid: String? = null
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        initializeFirebase()
        setContent { MemberGlobeNativeScreen(this) }
    }

    override fun onResume() {
        super.onResume()
        // osmdroid views are resumed by the composable's lifecycle effect.
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
            val app = FirebaseApp.initializeApp(this, options) ?: FirebaseApp.getInstance()
            auth = FirebaseAuth.getInstance(app)
            firestore = FirebaseFirestore.getInstance(app)
            auth?.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                memberListener?.remove()
                locationsListener?.remove()
                membersListener?.remove()
                memberListener = null
                locationsListener = null
                membersListener = null
                mapListenersUid = null
                sharedLocations = emptyList()
                approvedMembers = emptyMap()
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
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                busy = false
                message = "Google did not return an ID token. Verify the Web OAuth client ID and Android SHA-1 in Firebase."
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth?.signInWithCredential(credential)?.addOnCompleteListener { task ->
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
                ref.set(profile).addOnFailureListener { e -> message = "Could not create member request: ${e.message}" }
            } else {
                ref.update(
                    mapOf(
                        "displayName" to (user.displayName ?: user.email?.substringBefore("@") ?: "Member"),
                        "email" to (user.email ?: ""),
                        "photoURL" to (user.photoUrl?.toString() ?: ""),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            memberListener = ref.addSnapshotListener { doc, error ->
                if (error != null) {
                    message = "Could not load membership status: ${error.message}"
                    return@addSnapshotListener
                }
                member = doc?.let { snapshotToMap(it) }
                if (member?.get("status") == "approved") attachMapListeners(uid)
                else detachMapListeners()
            }
        }.addOnFailureListener { e ->
            message = "Could not load member profile: ${e.message}. Check Firestore rules."
        }
    }

    private fun attachMapListeners(uid: String) {
        if (mapListenersUid == uid) return
        val db = firestore ?: return
        mapListenersUid = uid
        locationsListener?.remove()
        membersListener?.remove()
        // These filters match the Firestore rules for approved members' shared points.
        locationsListener = db.collection("locations")
            .whereEqualTo("sharing", true)
            .whereEqualTo("memberStatus", "approved")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    message = "Map locations could not load: ${error.message}"
                    return@addSnapshotListener
                }
                sharedLocations = snapshot?.documents?.map { snapshotToMap(it) + ("documentId" to it.id) } ?: emptyList()
                if (sharedLocations.isEmpty()) message = "Map is ready. No approved members are sharing a location yet."
            }
        membersListener = db.collection("members")
            .whereEqualTo("status", "approved")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    message = "Member names could not load for the map: ${error.message}"
                    return@addSnapshotListener
                }
                approvedMembers = snapshot?.documents?.associate { it.id to snapshotToMap(it) } ?: emptyMap()
            }
    }

    private fun detachMapListeners() {
        locationsListener?.remove()
        membersListener?.remove()
        locationsListener = null
        membersListener = null
        mapListenersUid = null
        sharedLocations = emptyList()
        approvedMembers = emptyMap()
    }

    private fun snapshotToMap(snapshot: DocumentSnapshot): Map<String, Any?> = snapshot.data ?: emptyMap()

    fun openWebDashboard() {
        if (BuildConfig.FIREBASE_PROJECT_ID.isBlank()) {
            message = "Set your Firebase project ID before opening the web dashboard."
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${BuildConfig.FIREBASE_PROJECT_ID}.web.app/")))
        } catch (e: Exception) {
            message = "Could not open the web dashboard: ${e.message}"
        }
    }

    fun signOutMember() {
        memberListener?.remove()
        memberListener = null
        detachMapListeners()
        member = null
        auth?.signOut()
        GoogleSignIn.getClient(this, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut()
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
            message = "Location permission is missing. Allow it in Android Settings and try again."
            return
        }
        busy = true
        message = "Getting your current GPS location…"
        db.collection("members").document(user.uid).update(
            mapOf("locationSharing" to true, "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            locationClient.getCurrentLocation(
                if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location: Location? ->
                if (location == null) {
                    busy = false
                    message = "Android returned no GPS fix. Turn on Location, allow precise location, then try outdoors again."
                    return@addOnSuccessListener
                }
                val point = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "lat" to location.latitude,
                    "lng" to location.longitude,
                    "accuracy" to location.accuracy,
                    "sharing" to true,
                    "memberStatus" to "approved",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("locations").document(user.uid).set(point)
                    .addOnSuccessListener {
                        busy = false
                        message = "GPS shared (accuracy about ${location.accuracy.toInt()} m). The map will update automatically. Tap Update to refresh your location."
                    }.addOnFailureListener { e ->
                        busy = false
                        message = "GPS was found but Firestore rejected the location: ${e.message}"
                    }
            }.addOnFailureListener { e ->
                busy = false
                message = "GPS lookup failed: ${e.message}"
            }
        }.addOnFailureListener { e ->
            busy = false
            message = "Could not enable location sharing in your member profile: ${e.message}"
        }
    }

    fun stopLocationSharing() {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        busy = true
        db.collection("members").document(user.uid).update(
            mapOf("locationSharing" to false, "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            db.collection("locations").document(user.uid).delete().addOnCompleteListener { task ->
                busy = false
                if (task.isSuccessful) message = "Location sharing is off and your shared point was removed."
                else message = "Sharing is off, but the old map point could not be removed: ${task.exception?.message}"
            }
        }.addOnFailureListener { e ->
            busy = false
            message = "Could not stop sharing: ${e.message}"
        }
    }

    companion object { private const val REQUEST_GOOGLE_SIGN_IN = 7341 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberGlobeNativeScreen(activity: MainActivity) {
    val user = activity.auth?.currentUser
    val member = activity.member
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) activity.shareCurrentLocation()
        else activity.message = "Location permission was denied. Your location has not been shared."
    }

    MaterialTheme {
        val status = member?.get("status")?.toString() ?: "loading"
        val displayName = member?.get("displayName")?.toString()?.takeIf { it.isNotBlank() }
            ?: user?.displayName ?: "Member"
        val email = member?.get("email")?.toString()?.takeIf { it.isNotBlank() } ?: user?.email.orEmpty()
        val photoUrl = member?.get("photoURL")?.toString()?.takeIf { it.isNotBlank() }
            ?: user?.photoUrl?.toString().orEmpty()

        if (user != null && status == "approved") {
            // Map-first dashboard: the map occupies the entire screen; controls float above it.
            Box(Modifier.fillMaxSize()) {
                NativeMemberMap(
                    points = activity.sharedLocations,
                    members = activity.approvedMembers,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 5.dp,
                    shadowElevation = 5.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Member Globe", style = MaterialTheme.typography.titleMedium)
                            Text("${activity.sharedLocations.size} member location(s) shared", style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Text("☰", style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("My profile · $displayName") },
                                    onClick = { menuExpanded = false },
                                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open admin / web dashboard") },
                                    onClick = { menuExpanded = false; activity.openWebDashboard() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    onClick = { menuExpanded = false; activity.signOutMember() }
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activity.message.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 3.dp
                        ) {
                            Text(activity.message, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (photoUrl.isNotBlank()) {
                                    AsyncImage(model = photoUrl, contentDescription = "Google profile photo",
                                        modifier = Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Default.AccountCircle, contentDescription = "Profile",
                                        modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(email, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Text("LIVE MAP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                        if (fine || coarse) activity.shareCurrentLocation()
                                        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    },
                                    enabled = !activity.busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (activity.busy) "Updating…" else "◎  Share my GPS") }
                                OutlinedButton(
                                    onClick = { activity.stopLocationSharing() },
                                    enabled = !activity.busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Stop sharing") }
                            }
                            Text("Location sharing is optional. GPS refreshes when you tap Share my GPS; background tracking is not enabled.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            Scaffold(topBar = { TopAppBar(title = { Text("Member Globe") }) }) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Member Globe", style = MaterialTheme.typography.headlineMedium)
                    Text("Member location dashboard", style = MaterialTheme.typography.bodyMedium)
                    if (user == null) {
                        Text("Sign in with Google. New accounts wait for admin approval before member features are available.")
                        Button(
                            onClick = { activity.startGoogleSignIn() },
                            enabled = !activity.busy && activity.auth != null && BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (activity.busy) "Please wait…" else "Continue with Google") }
                    } else {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (photoUrl.isNotBlank()) AsyncImage(model = photoUrl, contentDescription = "Google profile photo",
                                    modifier = Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                else Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(56.dp))
                                Column {
                                    Text(displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(email, style = MaterialTheme.typography.bodySmall)
                                    Text("Membership: ${status.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        when (status) {
                            "pending" -> Text("Your membership request is pending admin approval.")
                            "loading" -> Text("Loading your membership profile…")
                            else -> Text("Membership is not active. Contact your administrator.")
                        }
                        OutlinedButton(onClick = { activity.openWebDashboard() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open web dashboard and admin tools")
                        }
                        OutlinedButton(onClick = { activity.signOutMember() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                    }
                    if (activity.message.isNotBlank()) Text(activity.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Your location stays off until you choose to share it.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NativeMemberMap(
    points: List<Map<String, Any?>>,
    members: Map<String, Map<String, Any?>>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(12.8797, 121.7740))
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()
            points.forEach { point ->
                val lat = (point["lat"] as? Number)?.toDouble() ?: return@forEach
                val lng = (point["lng"] as? Number)?.toDouble() ?: return@forEach
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@forEach
                val uid = point["uid"]?.toString() ?: point["documentId"]?.toString().orEmpty()
                val profile = members[uid]
                val name = profile?.get("displayName")?.toString()?.takeIf { it.isNotBlank() } ?: "Member"
                val marker = Marker(map).apply {
                    position = GeoPoint(lat, lng)
                    title = name
                    snippet = profile?.get("email")?.toString().orEmpty()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(marker)
            }
            map.invalidate()
            if (points.size == 1) {
                val p = points.first()
                val lat = (p["lat"] as? Number)?.toDouble()
                val lng = (p["lng"] as? Number)?.toDouble()
                if (lat != null && lng != null) map.controller.animateTo(GeoPoint(lat, lng))
            } else if (points.size > 1) {
                val valid = points.mapNotNull { p ->
                    val lat = (p["lat"] as? Number)?.toDouble()
                    val lng = (p["lng"] as? Number)?.toDouble()
                    if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) GeoPoint(lat, lng) else null
                }
                if (valid.isNotEmpty()) map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(valid), true)
            }
        }
    )
}
