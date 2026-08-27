package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import net.morsecode.net.DiscoveredDevice
import net.morsecode.ui.AppState

/**
 * Home: device discovery + quick send/receive (Section A).
 *
 * Shows peers advertised over mDNS, the incoming-transfer prompt (Section 5),
 * and live receive progress. Selecting a peer hands it to the send flow.
 */
@Composable
fun HomeScreen(app: AppState, onSendTo: (DiscoveredDevice) -> Unit = {}) {
    val devices by app.devices
    val pending by app.pendingIncoming
    val receive by app.receiveProgress

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nearby devices", style = MaterialTheme.typography.titleLarge)

        if (devices.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Looking for devices on this network…")
                    Text(
                        "Make sure the other device has Morse Code open on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(device) { onSendTo(device) }
                }
            }
        }

        receive?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Receiving ${p.filename}")
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${p.verifiedChunks}/${p.totalChunks} chunks", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    pending?.let { incoming ->
        AlertDialog(
            onDismissRequest = { app.controller.declineIncoming() },
            title = { Text("Incoming transfer") },
            text = { Text("${incoming.hello.deviceName} wants to connect.") },
            confirmButton = {
                TextButton(onClick = { app.controller.acceptIncoming() }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { app.controller.declineIncoming() }) { Text("Decline") }
            },
        )
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${device.deviceType} · ${device.host}:${device.port}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
