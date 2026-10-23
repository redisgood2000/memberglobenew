package com.memberglobe.nativeapp

import android.content.ActivityNotFoundException
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
    var notice by remember {
        mutableStateOf(
            "Use Member Globe's hosted sign-in so AppDeploy can create and manage your session."
        )
    }

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
                    "Sign in using the official Member Globe website. AppDeploy authentication is handled by the hosted app; this Android app will not treat a Google ID token as an AppDeploy access token.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(MEMBER_GLOBE_WEB_URL)
                            )
                            context.startActivity(intent)
                            notice = "Opened Member Globe in your browser. Complete sign-in there and use the hosted app for authenticated member features."
                        } catch (_: ActivityNotFoundException) {
                            notice = "No browser is available to open Member Globe. Install or enable a browser and try again."
                        } catch (e: Exception) {
                            notice = "Could not open Member Globe: ${e.message ?: e.javaClass.simpleName}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open secure Member Globe sign-in")
                }
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Important: AppDeploy does not currently document a native token-exchange flow for this app. Browser sign-in is supported, but its browser session is not automatically shared with native API calls. Do not enter credentials into an imitation login screen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
