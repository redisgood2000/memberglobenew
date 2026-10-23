package com.memberglobe.nativeapp

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

private const val GOOGLE_WEB_CLIENT_ID =
    "872980250155-d8mldeem6beb9jcjnj54os2amkc73k5q.apps.googleusercontent.com"

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
    val activity = context as? Activity
    var notice by remember { mutableStateOf("Sign in with Google to begin.") }
    var busy by remember { mutableStateOf(false) }
    var signedInEmail by remember { mutableStateOf<String?>(null) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        busy = false
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            signedInEmail = account.email ?: account.id
            // Do not persist or log the ID token. A verified backend session exchange
            // must be configured before enabling protected API calls.
            notice = "Google account selected. Member Globe API sign-in is not complete yet: the backend still needs a supported, verified session exchange."
        } catch (e: ApiException) {
            notice = "Google Sign-In failed: statusCode=${e.statusCode}; message=${e.message ?: "no details"}. Please send this exact message."
        } catch (e: Exception) {
            notice = "Google Sign-In failed: ${e.javaClass.simpleName}; ${e.message ?: "no details"}"
        }
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
                    "Choose your Google account. This test checks Google account sign-in only; member API authentication is not configured yet.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    enabled = !busy && activity != null,
                    onClick = {
                        val currentActivity = activity
                        if (currentActivity == null) {
                            notice = "Cannot start Google Sign-In because the activity is unavailable."
                        } else {
                            busy = true
                            notice = "Opening Google Sign-In…"
                            val options = GoogleSignInOptions.Builder(
                                GoogleSignInOptions.DEFAULT_SIGN_IN
                            )
                                .requestEmail()
                                .requestIdToken(GOOGLE_WEB_CLIENT_ID)
                                .build()
                            val client = GoogleSignIn.getClient(currentActivity, options)
                            signInLauncher.launch(client.signInIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in…" else "Continue with Google")
                }
                if (signedInEmail != null) {
                    Text("Google account selected: $signedInEmail", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (signedInEmail != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "This build does not share your location or access member/chat APIs yet. Do not treat Google account selection as a completed Member Globe login.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
