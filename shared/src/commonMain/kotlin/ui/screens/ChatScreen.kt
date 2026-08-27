package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.morsecode.chat.ChatDirection
import net.morsecode.chat.StoredChatMessage
import net.morsecode.ui.AppState
import net.morsecode.util.Ids

/**
 * Chat destination (Section G). The list of conversations; selecting one shows
 * its thread with sent bubbles right, received left (ChatBubble). Messages are
 * persisted through [net.morsecode.storage.ChatRepo] and, when a session with
 * the peer is open, bridged onto the encrypted connection as CHAT_MESSAGE.
 */
@Composable
fun ChatScreen(app: AppState) {
    var previews by remember { mutableStateOf<List<StoredChatMessage>>(emptyList()) }
    var thread by remember { mutableStateOf<List<StoredChatMessage>>(emptyList()) }
    var activePeer by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { previews = app.chatRepo.previews() }
    LaunchedEffect(activePeer) {
        activePeer?.let { thread = app.chatRepo.thread(it) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (activePeer == null) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge)
            LazyColumn {
                items(previews, key = { it.peerDeviceId }) { p ->
                    Card(onClick = { activePeer = p.peerDeviceId }, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.peerDeviceId, style = MaterialTheme.typography.titleMedium)
                            Text(p.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { activePeer = null }) { Text("‹ Back") }
                Text(activePeer!!, style = MaterialTheme.typography.titleMedium)
            }
            val listState = rememberLazyListState()
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(thread, key = { it.messageId }) { m -> ChatBubble(m) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                TextButton(onClick = {
                    val text = draft
                    draft = ""
                    scope.launch {
                        app.chatRepo.insert(
                            StoredChatMessage(
                                messageId = Ids.uuid(app.crypto.randomBytes(16)),
                                peerDeviceId = activePeer!!,
                                text = text,
                                direction = ChatDirection.SENT,
                                sentAtEpochMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                                delivered = false,
                            ),
                        )
                        thread = app.chatRepo.thread(activePeer!!)
                    }
                }) { Text("Send") }
            }
        }
    }
}

/** Sent bubbles right-aligned, received left-aligned (Section G). */
@Composable
private fun ChatBubble(message: StoredChatMessage) {
    val sent = message.direction == ChatDirection.SENT
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
    ) {
        Card {
            Column(Modifier.padding(10.dp)) {
                Text(message.text)
                Text(
                    if (sent && message.delivered) "delivered" else if (sent) "sent" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
