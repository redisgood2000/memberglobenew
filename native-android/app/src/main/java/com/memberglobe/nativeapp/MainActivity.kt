package com.memberglobe.nativeapp

import android.content.Intent
import android.net.Uri
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

private const val MEMBER_GLOBE_WEB_URL = "https://member-globe-t5uo18.v2.appdeploy.ai/"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MemberGlobeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberGlobeApp() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Member Globe") }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Member Globe", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Sign in securely to see your member account and shared locations.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(MEMBER_GLOBE_WEB_URL))
                            )
                        } catch (_: Exception) {
                            notice = "Could not open the Member Globe website. Check your internet connection."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Member Globe sign-in")
                }
                Text(
                    "Native Google sign-in is not connected yet. This button opens the existing website, where its supported Google sign-in can be used. Signing in there does not yet create a session inside this Android app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (notice.isNotBlank()) {
                    Text(notice, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Your location is not shared by this starter app. Location sharing should be opt-in and visible to authorized members.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
