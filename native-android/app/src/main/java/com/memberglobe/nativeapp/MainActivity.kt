package com.memberglobe.nativeapp

import android.app.Activity
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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var notice by remember { mutableStateOf("Sign in with Google to begin.") }
    var busy by remember { mutableStateOf(false) }
    var signedInEmail by remember { mutableStateOf<String?>(null) }

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
                    "Choose your Google account. Your Google ID token is kept in memory only and is not logged or saved.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    enabled = !busy && activity != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            notice = "Waiting for Google account selection…"
                            try {
                                // Use the standard Google ID option and allow accounts that
                                // have not previously authorized this app. This is an alternative
                                // Credential Manager flow for diagnosing picker cancellation.
                                val googleSignInOption = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(GOOGLE_WEB_CLIENT_ID)
                                    .setAutoSelectEnabled(false)
                                    .build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleSignInOption)
                                    .build()
                                val result = credentialManager.getCredential(
                                    context = activity!!,
                                    request = request
                                )
                                val credential = result.credential
                                if (
                                    credential is CustomCredential &&
                                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                ) {
                                    val googleCredential =
                                        GoogleIdTokenCredential.createFrom(credential.data)
                                    // Do not persist or log the ID token. A verified backend session
                                    // exchange must be configured before enabling protected API calls.
                                    signedInEmail = googleCredential.id
                                    notice = "Google account selected. Member Globe API sign-in is not complete yet: the backend still needs a supported, verified session exchange."
                                } else {
                                    notice = "Google returned an unsupported credential type. Please try again."
                                }
                            } catch (e: GetCredentialException) {
                                notice = when (e) {
                                    is androidx.credentials.exceptions.NoCredentialException ->
                                        "Google couldn't offer a sign-in credential. Check that Google Play services and your Google account are up to date, then try again. If it repeats, the OAuth Android/Web client configuration needs checking."
                                    else -> "Google sign-in error: ${e.javaClass.simpleName}; type=${e.type}; message=${e.message ?: "no details"}. Check OAuth configuration and try again."
                                }
                            } catch (_: Exception) {
                                notice = "Google sign-in failed. Check the OAuth client configuration and try again."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in…" else "Continue with Google")
                }
                if (signedInEmail != null) {
                    Text("Google account selected: ${signedInEmail}", style = MaterialTheme.typography.bodyMedium)
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
