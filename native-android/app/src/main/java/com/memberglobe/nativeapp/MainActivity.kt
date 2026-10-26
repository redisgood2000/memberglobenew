package com.memberglobe.nativeapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

private const val MEMBER_GLOBE_WEB_URL = "https://member-globe-t5uo18.v2.appdeploy.ai/"
private const val MEMBER_GLOBE_WEB_HOST = "member-globe-t5uo18.v2.appdeploy.ai"

private val Ink = Color(0xFF101C2E)
private val Blue = Color(0xFF2563EB)
private val Muted = Color(0xFF64748B)
private val Panel = Color(0xFFF8FAFC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContent { MemberGlobeApp() }
    }

    override fun onResume() {
        super.onResume()
        // Map lifecycle is handled by the screen's MapView reference.
    }
}


@Composable
private fun MemberGlobeApp() {
    var showPortal by remember { mutableStateOf(false) }
    BackHandler(enabled = showPortal) { showPortal = false }
    if (showPortal) EmbeddedAdminPortal(onBack = { showPortal = false })
    else AdminMapScreen(onOpenPortal = { showPortal = true })
}

@Composable
private fun EmbeddedAdminPortal(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Admin portal") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Surface(color = Color.White, shadowElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to map") }
                Column(Modifier.weight(1f)) {
                    Text("MEMBER GLOBE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.sp)
                    Text(pageTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                }
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                IconButton(onClick = { loadError = null; loading = true; webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh portal")
                }
            }
        }
        loadError?.let { error ->
            Surface(color = Color(0xFFFFF4D6), modifier = Modifier.fillMaxWidth()) {
                Text(error, color = Color(0xFF805A00), modifier = Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(true)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true; loadError = null
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                loadError = "The portal could not load. Check your internet connection and tap refresh."
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return request?.url?.scheme != "https"
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                        }
                        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                            val host = runCatching { android.net.Uri.parse(origin).host }.getOrNull()
                            val hasLocationPermission =
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            callback?.invoke(origin, host == MEMBER_GLOBE_WEB_HOST && hasLocationPermission, false)
                        }
                    }
                    loadUrl(MEMBER_GLOBE_WEB_URL)
                }
            },
            update = { webView = it }
        )
    }
    DisposableEffect(Unit) {
        onDispose { webView?.apply { stopLoading(); destroy() }; webView = null }
    }
}

@Composable
private fun AdminMapScreen(onOpenPortal: () -> Unit) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var notice by remember { mutableStateOf("Sign in to the secure portal to manage real member data.") }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            background = Panel,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Box(Modifier.fillMaxSize().background(Panel)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        controller.setZoom(2.8)
                        controller.setCenter(GeoPoint(15.0, 10.0))
                        mapView = this
                    }
                },
                update = { mapView = it }
            )

            // Top floating admin identity panel
            Card(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MEMBER GLOBE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.2.sp)
                        Text("Admin map", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFFFF4D6)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF9A6700), modifier = Modifier.size(15.dp))
                            Text("ADMIN", color = Color(0xFF9A6700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Map controls
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomIn() },
                    containerColor = Color.White,
                    contentColor = Ink,
                    modifier = Modifier.size(48.dp)
                ) { Text("+", fontSize = 25.sp, fontWeight = FontWeight.Normal) }
                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomOut() },
                    containerColor = Color.White,
                    contentColor = Ink,
                    modifier = Modifier.size(48.dp)
                ) { Text("−", fontSize = 25.sp, fontWeight = FontWeight.Normal) }
                FloatingActionButton(
                    onClick = { mapView?.controller?.animateTo(GeoPoint(15.0, 10.0)); mapView?.controller?.setZoom(2.8) },
                    containerColor = Color.White,
                    contentColor = Blue,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.MyLocation, contentDescription = "Reset global map") }
            }

            // Bottom floating admin action sheet
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                        Text("MEMBER LOCATIONS", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Muted)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    Text("Your world, at a glance.", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "The map is ready. Live member pins and counts will appear after the native app is connected to your authorized Firebase location data.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Muted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { notice = "Member totals are not shown until live Firebase data is connected."; },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Members")
                        }
                        Button(
                            onClick = onOpenPortal,
                            modifier = Modifier.weight(1.25f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Open admin portal")
                        }
                    }
                    Surface(color = Color(0xFFF1F5F9), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = Blue, modifier = Modifier.size(17.dp))
                            Text(notice, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { notice = "Sign in to the secure portal to manage real member data." }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset message", tint = Muted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
