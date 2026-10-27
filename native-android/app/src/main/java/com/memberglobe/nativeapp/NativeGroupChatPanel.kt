package com.memberglobe.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NativeGroupChatPanel(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val myUid = activity.auth?.currentUser?.uid.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(activity.chatMessages.size) {
        if (activity.chatMessages.isNotEmpty()) listState.animateScrollToItem(activity.chatMessages.lastIndex)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("💬 Member Globe Chat", style = MaterialTheme.typography.titleLarge)
                    Text("Approved members · live messages", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close chat") }
            }
            HorizontalDivider()
            if (activity.chatMessages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No messages yet. Say hello! 👋", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = false
                ) {
                    items(activity.chatMessages, key = { it["documentId"]?.toString().orEmpty() }) { msg ->
                        val mine = msg["senderUid"]?.toString() == myUid
                        val photo = msg["senderPhotoURL"]?.toString().orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            if (!mine) {
                                if (photo.startsWith("https://")) {
                                    AsyncImage(photo, contentDescription = "Sender photo",
                                        modifier = Modifier.padding(end = 6.dp).size(30.dp).clip(CircleShape))
                                } else {
                                    Box(Modifier.padding(end = 6.dp).size(30.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center) { Text("🙂") }
                                }
                            }
                            Column(
                                modifier = Modifier.widthIn(max = 280.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (mine) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (!mine) Text(msg["senderName"]?.toString() ?: "Member",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(msg["text"]?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                val timestamp = msg["createdAt"] as? com.google.firebase.Timestamp
                                if (timestamp != null) {
                                    Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp.seconds * 1000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.End))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("😊", "❤️", "👍", "😂", "🎉", "👋").forEach { emoji ->
                    TextButton(onClick = { if (draft.length < 1998) draft += emoji }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Text(emoji, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 2000) draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message… 😊") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                FilledIconButton(
                    onClick = { val value = draft.trim(); if (value.isNotEmpty()) { activity.sendGroupMessage(value); draft = "" } },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.Send, contentDescription = "Send message") }
            }
        }
    }
}
