package com.memberglobe.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.Geocoder
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
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
import com.google.firebase.messaging.FirebaseMessaging
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

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
    var memberAddresses by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val addressLookupsInProgress = mutableSetOf<String>()
    var isAdmin by mutableStateOf(false)
        private set
    var pendingMembers by mutableStateOf<List<Map<String, Any?>>>(emptyList())
        private set
    var pendingRequestsError by mutableStateOf("")
        private set
    private var adminListener: ListenerRegistration? = null
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var memberListener: ListenerRegistration? = null
    private var locationsListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var chatListener: ListenerRegistration? = null
    var chatMessages by mutableStateOf<List<Map<String, Any?>>>(emptyList())
        private set
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
                .setGcmSenderId("622920961765")
                .build()
            val app = FirebaseApp.initializeApp(this, options) ?: FirebaseApp.getInstance()
            auth = FirebaseAuth.getInstance(app)
            firestore = FirebaseFirestore.getInstance(app)
            auth?.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                memberListener?.remove()
                locationsListener?.remove()
                membersListener?.remove()
                chatListener?.remove()
                chatMessages = emptyList()
                memberListener = null
                locationsListener = null
                membersListener = null
                mapListenersUid = null
                sharedLocations = emptyList()
                approvedMembers = emptyMap()
                member = null
                adminListener?.remove()
                adminListener = null
                isAdmin = false
                pendingMembers = emptyList()
                if (user != null) {
                    checkAdminAccess(user.uid)
                    ensureMemberProfile(user.uid)
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        firestore?.collection("deviceTokens")?.document(user.uid)?.set(
                            mapOf("uid" to user.uid, "token" to token, "updatedAt" to FieldValue.serverTimestamp())
                        )
                    }
                }
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

    private fun checkAdminAccess(uid: String) {
        val db = firestore ?: return
        db.collection("admins").document(uid).get()
            .addOnSuccessListener { snapshot ->
                isAdmin = snapshot.exists()
                adminListener?.remove()
                adminListener = null
                pendingMembers = emptyList()
                pendingRequestsError = ""
                if (snapshot.exists()) {
                    adminListener = db.collection("members")
                        .whereEqualTo("status", "pending")
                        .addSnapshotListener { pending, error ->
                            if (error != null) {
                                pendingRequestsError = "Could not load requests: ${error.message ?: "Firestore permission or network error"}"
                                message = pendingRequestsError
                                return@addSnapshotListener
                            }
                            pendingRequestsError = ""
                            pendingMembers = pending?.documents?.map { snapshotToMap(it) + ("documentId" to it.id) } ?: emptyList()
                        }
                } else {
                    // Most signed-in users are members, not admins. Missing an admins/{uid}
                    // document is normal and must not be shown as an app-wide error.
                    pendingRequestsError = ""
                }
            }
            .addOnFailureListener { e ->
                isAdmin = false
                // Keep admin-configuration diagnostics out of the member screen.
                // If an actual admin cannot open the admin panel, surface this in the admin UI.
                pendingRequestsError = "Admin access check failed: ${e.message ?: "Firestore permission or network error"}"
            }
    }

    fun refreshPendingRequests() {
        val uid = auth?.currentUser?.uid ?: run {
            pendingRequestsError = "Please sign in again."
            return
        }
        if (!isAdmin) {
            checkAdminAccess(uid)
            return
        }
        val db = firestore ?: return
        pendingRequestsError = ""
        db.collection("members").whereEqualTo("status", "pending").get()
            .addOnSuccessListener { snapshot ->
                pendingMembers = snapshot.documents.map { snapshotToMap(it) + ("documentId" to it.id) }
                pendingRequestsError = ""
                message = "Requests refreshed: ${pendingMembers.size} pending."
            }
            .addOnFailureListener { e ->
                pendingRequestsError = "Could not refresh requests: ${e.message ?: "Firestore permission or network error"}"
                message = pendingRequestsError
            }
    }

    fun reviewMember(uid: String, approve: Boolean) {
        if (!isAdmin) {
            message = "Admin access is required to review membership requests."
            return
        }
        val db = firestore ?: return
        val reviewer = auth?.currentUser?.uid ?: return
        busy = true
        val newStatus = if (approve) "approved" else "rejected"
        db.collection("members").document(uid).update(
            mapOf(
                "status" to newStatus,
                "reviewedAt" to FieldValue.serverTimestamp(),
                "reviewedBy" to reviewer,
                "updatedAt" to FieldValue.serverTimestamp(),
                "locationSharing" to false
            )
        ).addOnSuccessListener {
            busy = false
            message = if (approve) "Member approved." else "Membership request rejected."
        }.addOnFailureListener { e ->
            busy = false
            message = "Could not update membership: ${e.message}"
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
                if (member?.get("status") == "approved") {
                    attachMapListeners(uid)
                    attachChatListener()
                    if (member?.get("locationSharing") == true) startLocationTrackingService(uid)
                } else {
                    stopLocationTrackingService()
                    detachMapListeners()
                    chatListener?.remove()
                    chatListener = null
                    chatMessages = emptyList()
                }
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

    private fun attachChatListener() {
        if (chatListener != null) return
        val db = firestore ?: return
        chatListener = db.collection("groupMessages").orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    message = "Group chat could not load: ${error.message}"
                    return@addSnapshotListener
                }
                chatMessages = snapshot?.documents?.map { snapshotToMap(it) + ("documentId" to it.id) }?.reversed() ?: emptyList()
            }
    }

    fun sendGroupMessage(text: String) {
        val clean = text.trim()
        val user = auth?.currentUser
        val profile = member
        val db = firestore
        if (user == null || db == null || profile?.get("status") != "approved") {
            message = "Only approved members can send group messages."
            return
        }
        if (clean.isBlank() || clean.length > 2000) return
        db.collection("groupMessages").add(mapOf(
            "senderUid" to user.uid,
            "senderName" to (profile["displayName"]?.toString()?.ifBlank { "Member" } ?: "Member"),
            "senderPhotoURL" to (profile["photoURL"]?.toString() ?: ""),
            "text" to clean,
            "createdAt" to FieldValue.serverTimestamp()
        )).addOnFailureListener { e -> message = "Message not sent: ${e.message}" }
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

    fun resolveMemberAddress(uid: String, latitude: Double, longitude: Double) {
        if (memberAddresses[uid]?.isNotBlank() == true) return
        synchronized(addressLookupsInProgress) {
            if (!addressLookupsInProgress.add(uid)) return
        }
        Thread {
            val address = try {
                if (!Geocoder.isPresent()) null
                else {
                    @Suppress("DEPRECATION")
                    Geocoder(this, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                        ?.firstOrNull()?.getAddressLine(0)
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                synchronized(addressLookupsInProgress) { addressLookupsInProgress.remove(uid) }
                if (!address.isNullOrBlank()) memberAddresses = memberAddresses + (uid to address)
                else memberAddresses = memberAddresses + (uid to "Address unavailable")
            }
        }.start()
    }

    fun signOutMember() {
        adminListener?.remove()
        adminListener = null
        isAdmin = false
        pendingMembers = emptyList()
        pendingRequestsError = ""
        memberListener?.remove()
        memberListener = null
        detachMapListeners()
        member = null
        stopLocationTrackingService()
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
            startLocationTrackingService(user.uid)
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
        stopLocationTrackingService()
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
    val overlayAlpha = 0.75f // Fixed at 25% transparency.
    var showProfile by remember { mutableStateOf(false) }
    var showMembersPanel by remember { mutableStateOf(false) }
    var showChatPanel by remember { mutableStateOf(false) }
    var selectedMemberUid by remember { mutableStateOf<String?>(null) }
    var showAdminPanel by remember { mutableStateOf(false) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) activity.shareCurrentLocation()
        else activity.message = "Location permission was denied. Your location has not been shared."
    }
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(user?.uid, member?.get("status")) {
        if (user != null && member?.get("status") == "approved" && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MaterialTheme {
        val status = member?.get("status")?.toString() ?: "loading"
        val displayName = member?.get("displayName")?.toString()?.takeIf { it.isNotBlank() }
            ?: user?.displayName ?: "Member"
        val email = member?.get("email")?.toString()?.takeIf { it.isNotBlank() } ?: user?.email.orEmpty()
        val photoUrl = member?.get("photoURL")?.toString()?.takeIf { it.isNotBlank() }
            ?: user?.photoUrl?.toString().orEmpty()

        LaunchedEffect(showMembersPanel, activity.sharedLocations) {
            if (showMembersPanel) {
                activity.sharedLocations.forEach { point ->
                    val uid = point["uid"]?.toString() ?: point["documentId"]?.toString().orEmpty()
                    val lat = (point["lat"] as? Number)?.toDouble()
                    val lng = (point["lng"] as? Number)?.toDouble()
                    if (uid.isNotBlank() && lat != null && lng != null) {
                        activity.resolveMemberAddress(uid, lat, lng)
                    }
                }
            }
        }

        if (user != null && activity.isAdmin && showAdminPanel) {
            NativeAdminPanel(activity = activity, modifier = Modifier.fillMaxSize(), onBack = { showAdminPanel = false })
        } else if (user != null && status == "approved") {
            // Map-first dashboard: the map occupies the entire screen; controls float above it.
            Box(Modifier.fillMaxSize()) {
                NativeMemberMap(
                    points = activity.sharedLocations,
                    members = activity.approvedMembers,
                    selectedMemberUid = selectedMemberUid,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = overlayAlpha),
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
                        IconButton(onClick = { showMembersPanel = !showMembersPanel }) {
                            Icon(Icons.Default.Groups, contentDescription = "Open member list")
                        }
                        IconButton(onClick = { showChatPanel = !showChatPanel }) {
                            BadgedBox(badge = { if (activity.chatMessages.isNotEmpty()) Badge { Text(activity.chatMessages.size.coerceAtMost(99).toString()) } }) {
                                Icon(Icons.Default.Chat, contentDescription = "Open group chat")
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Text("☰", style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("My profile · $displayName") },
                                    onClick = { menuExpanded = false; showProfile = !showProfile },
                                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                                )
                                HorizontalDivider()
                                if (activity.isAdmin) {
                                    DropdownMenuItem(
                                        text = { Text("Native admin panel (${activity.pendingMembers.size} pending)") },
                                        onClick = { menuExpanded = false; showAdminPanel = true }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    onClick = { menuExpanded = false; activity.signOutMember() }
                                )
                            }
                        }
                    }
                }

                // Compact floating actions: only the GPS icon is shown for location sharing.
                val isSharingLocation = member?.get("locationSharing") == true
                FloatingActionButton(
                    onClick = {
                        if (isSharingLocation) activity.stopLocationSharing()
                        else {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) activity.shareCurrentLocation()
                            else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).size(54.dp),
                    containerColor = if (isSharingLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isSharingLocation) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(6.dp)
                ) {
                    Icon(
                        imageVector = if (isSharingLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                        contentDescription = if (isSharingLocation) "Turn GPS sharing off" else "Turn GPS sharing on",
                        modifier = Modifier.size(27.dp)
                    )
                }

                if (showProfile) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = overlayAlpha),
                        tonalElevation = 4.dp
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("My profile", style = MaterialTheme.typography.titleMedium)
                            Text(displayName)
                            Text(email, style = MaterialTheme.typography.bodySmall)
                            Text("Membership: Approved", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (showChatPanel) {
                    NativeGroupChatPanel(
                        activity = activity,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.72f).padding(horizontal = 10.dp, vertical = 12.dp),
                        onClose = { showChatPanel = false }
                    )
                }

                if (showMembersPanel) {
                    Surface(
                        modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.88f).padding(start = 10.dp, top = 74.dp, bottom = 80.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = overlayAlpha),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Members", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Text("${activity.approvedMembers.size}", style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { showMembersPanel = false }) { Text("✕") }
                            }
                            HorizontalDivider()
                            if (activity.approvedMembers.isEmpty()) {
                                Text("No approved members found.", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    activity.approvedMembers.entries.sortedBy {
                                        it.value["displayName"]?.toString()?.lowercase().orEmpty()
                                    }.forEach { entry ->
                                        val profile = entry.value
                                        val sharing = activity.sharedLocations.any {
                                            (it["uid"]?.toString() ?: it["documentId"]?.toString()) == entry.key
                                        }
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                                selectedMemberUid = entry.key
                                                val locationPoint = activity.sharedLocations.firstOrNull {
                                                    (it["uid"]?.toString() ?: it["documentId"]?.toString()) == entry.key
                                                }
                                                if (locationPoint != null) {
                                                    val lat = (locationPoint["lat"] as? Number)?.toDouble()
                                                    val lng = (locationPoint["lng"] as? Number)?.toDouble()
                                                    if (lat != null && lng != null) activity.resolveMemberAddress(entry.key, lat, lng)
                                                }
                                            },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            val photo = profile["photoURL"]?.toString().orEmpty()
                                            if (photo.isNotBlank()) {
                                                AsyncImage(model = photo, contentDescription = "Member profile photo",
                                                    modifier = Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                            } else {
                                                Icon(Icons.Default.AccountCircle, contentDescription = null,
                                                    modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(profile["displayName"]?.toString()?.ifBlank { "Member" } ?: "Member",
                                                    style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                                Text(profile["email"]?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                Text(
                                                    if (!sharing) "Address hidden · location not shared"
                                                    else "📍 " + (activity.memberAddresses[entry.key] ?: "Finding address…"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 2
                                                )
                                                Text(if (sharing) "Sharing location" else "Location hidden",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (sharing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Box(Modifier.size(9.dp).clip(CircleShape).background(
                                                if (sharing) Color(0xFF16A34A) else Color(0xFF94A3B8)
                                            ))
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (activity.message.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 82.dp, bottom = 12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 3.dp
                    ) {
                        Text(activity.message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 3)
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
                        if (activity.isAdmin) {
                            Button(onClick = { showAdminPanel = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open native admin panel (${activity.pendingMembers.size} pending)")
                            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeAdminPanel(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Admin panel") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
                actions = {
                    Text("${activity.pendingMembers.size} pending", modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Membership approvals", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { activity.refreshPendingRequests() }, enabled = !activity.busy) { Text("Refresh") }
            }
            Text("Only accounts listed in the Firebase admins collection can approve or reject requests.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (activity.pendingRequestsError.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(activity.pendingRequestsError, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } else if (activity.message.isNotBlank()) {
                Text(activity.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (activity.pendingMembers.isEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (activity.pendingRequestsError.isBlank()) "No pending requests" else "Requests could not be loaded", style = MaterialTheme.typography.titleMedium)
                        Text(if (activity.pendingRequestsError.isBlank()) "No member records with status = pending were found. Tap Refresh to check again." else "Read the error above, correct the Firebase access/configuration issue, then tap Refresh.")
                    }
                }
            } else {
                activity.pendingMembers.forEach { pending ->
                    val uid = pending["documentId"]?.toString().orEmpty()
                    val name = pending["displayName"]?.toString()?.ifBlank { "Member" } ?: "Member"
                    val email = pending["email"]?.toString().orEmpty()
                    val photo = pending["photoURL"]?.toString().orEmpty()
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (photo.isNotBlank()) AsyncImage(
                                    model = photo, contentDescription = "Google profile photo",
                                    modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop
                                ) else Icon(Icons.Default.AccountCircle, contentDescription = null,
                                    modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.titleMedium)
                                    Text(email, style = MaterialTheme.typography.bodySmall)
                                    Text("Pending approval", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { activity.reviewMember(uid, true) },
                                    enabled = !activity.busy && uid.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Approve") }
                                OutlinedButton(
                                    onClick = { activity.reviewMember(uid, false) },
                                    enabled = !activity.busy && uid.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Reject") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to map") }
        }
    }
}

@Composable
private fun NativeMemberMap(
    points: List<Map<String, Any?>>,
    members: Map<String, Map<String, Any?>>,
    selectedMemberUid: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var lastZoomedUid by remember { mutableStateOf<String?>(null) }
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

                // Replace the standard map pin with this member's Google profile photo.
                val photoUrl = profile?.get("photoURL")?.toString().orEmpty()
                if (photoUrl.startsWith("https://") || photoUrl.startsWith("http://")) {
                    val request = ImageRequest.Builder(context)
                        .data(photoUrl)
                        .size(120)
                        .transformations(CircleCropTransformation())
                        .target(
                            onSuccess = { drawable ->
                                marker.icon = drawable
                                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                map.invalidate()
                            }
                        )
                        .build()
                    ImageLoader(context).enqueue(request)
                }
            }
            map.invalidate()
            if (selectedMemberUid != null) {
                if (selectedMemberUid != lastZoomedUid) {
                    val selectedPoint = points.firstOrNull { point ->
                        (point["uid"]?.toString() ?: point["documentId"]?.toString()) == selectedMemberUid
                    }
                    val lat = (selectedPoint?.get("lat") as? Number)?.toDouble()
                    val lng = (selectedPoint?.get("lng") as? Number)?.toDouble()
                    if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                        map.controller.setZoom(17.0)
                        map.controller.animateTo(GeoPoint(lat, lng))
                        lastZoomedUid = selectedMemberUid
                    }
                }
            } else if (points.size == 1) {
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
