package com.memberglobe.nativeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/** Consent-based live GPS publisher with a persistent notification while sharing is enabled. */
class MemberLocationService : Service() {
    private var callback: LocationCallback? = null
    private var uid: String? = null
    private var db: FirebaseFirestore? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
        db = FirebaseFirestore.getInstance(FirebaseApp.getInstance())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedUid = intent?.getStringExtra(EXTRA_UID)
        if (requestedUid.isNullOrBlank() || FirebaseAuth.getInstance().currentUser?.uid != requestedUid) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (uid == requestedUid && callback != null) return START_STICKY
        uid = requestedUid
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return START_NOT_STICKY
        }
        db?.collection("members")?.document(requestedUid)?.get()
            ?.addOnSuccessListener { member ->
                if (member.getString("status") != "approved" || member.getBoolean("locationSharing") != true) {
                    stopSelf()
                    return@addOnSuccessListener
                }
                beginLocationUpdates(requestedUid, fine)
            }
            ?.addOnFailureListener { stopSelf() }
        return START_STICKY
    }

    private fun beginLocationUpdates(memberUid: String, precise: Boolean) {
        if (callback != null) return
        val request = LocationRequest.Builder(
            if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        ).setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (FirebaseAuth.getInstance().currentUser?.uid != memberUid) {
                    stopSelf()
                    return
                }
                val point = hashMapOf<String, Any>(
                    "uid" to memberUid,
                    "lat" to location.latitude,
                    "lng" to location.longitude,
                    "accuracy" to location.accuracy,
                    "sharing" to true,
                    "memberStatus" to "approved",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db?.collection("locations")?.document(memberUid)?.set(point)
            }
        }
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, callback!!, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        callback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live location sharing", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Visible while Member Globe shares your live GPS location." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Member Globe location sharing is on")
            .setContentText("Updating your shared location while you move")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val EXTRA_UID = "member_uid"
        private const val CHANNEL_ID = "member_globe_live_location"
        private const val NOTIFICATION_ID = 2207
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val MIN_UPDATE_INTERVAL_MS = 3_000L
    }
}
