package com.memberglobe.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MemberGlobeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberGlobeApp() {
    var page by remember { mutableStateOf("Sign in") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Member Globe") }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (page == "Sign in") {
                    Icon(Icons.Default.Public, contentDescription = null,
                        modifier = Modifier.size(54.dp).align(Alignment.CenterHorizontally))
                    Text("Member Globe", style = MaterialTheme.typography.headlineMedium)
                    Text("Native Android starter")
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("Email") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") }, modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { page = "Dashboard" },
                        enabled = email.contains("@") && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Continue (preview only)") }
                    Text(
                        "Preview only: production authentication is not connected yet.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text("Welcome, ${email.substringBefore("@")}",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("Choose a section:")
                    listOf("Global Member Map", "Group Chat", "My Account", "Admin Review").forEach { label ->
                        Card(
                            onClick = { page = label },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when (label) {
                                        "Global Member Map" -> Icons.Default.Public
                                        "Group Chat" -> Icons.Default.Chat
                                        "My Account" -> Icons.Default.Person
                                        else -> Icons.Default.AdminPanelSettings
                                    },
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(label, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                    if (page != "Dashboard") {
                        Text(page, style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (page) {
                                "Global Member Map" -> "Map placeholder. Live GPS markers require the verified location API and a map provider."
                                "Group Chat" -> "Chat placeholder. Sending and receiving messages requires the verified chat API."
                                "My Account" -> "Profile and opt-in location-sharing preferences will appear here."
                                else -> "Admin approval requires the verified admin API and server-side role checks."
                            }
                        )
                        OutlinedButton(onClick = { page = "Dashboard" }) {
                            Text("Back to dashboard")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Location sharing should be opt-in and visible only to authorized members.")
                    TextButton(onClick = { page = "Sign in"; password = "" }) {
                        Text("Sign out")
                    }
                }
            }
        }
    }
}
