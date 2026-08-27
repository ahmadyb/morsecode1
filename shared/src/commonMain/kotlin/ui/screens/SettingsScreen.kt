package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.morsecode.storage.StoredTrustedDevice
import net.morsecode.ui.AppState

/**
 * Settings (Sections 9, 10, 12, A): theme, auto-accept scope, bandwidth
 * throttle, and trusted-device management.
 */
@Composable
fun SettingsScreen(app: AppState) {
    var trusted by remember { mutableStateOf<List<StoredTrustedDevice>>(emptyList()) }
    LaunchedEffect(Unit) { trusted = app.trustedRepo.all() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark theme", style = MaterialTheme.typography.titleMedium)
                    Text("Light / dark / system", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = app.darkTheme, onCheckedChange = { app.darkTheme = it })
            }
        }

        item { HorizontalDivider() }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-accept incoming", style = MaterialTheme.typography.titleMedium)
                    Text("Trusted devices only by default (Section 10)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = app.autoAcceptEnabled, onCheckedChange = { app.autoAcceptEnabled = it })
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("…from all devices", style = MaterialTheme.typography.titleMedium)
                    Text("Requires explicit confirmation (Section 10)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = app.autoAcceptAllDevices,
                    enabled = app.autoAcceptEnabled,
                    onCheckedChange = { app.autoAcceptAllDevices = it },
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Limit transfer speed", style = MaterialTheme.typography.titleMedium)
                    Text("Global token bucket (Section 12)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = app.throttleKbps != null,
                    onCheckedChange = { on -> app.throttleKbps = if (on) 10_000L else null },
                )
            }
        }

        item { HorizontalDivider() }

        item { Text("Trusted devices", style = MaterialTheme.typography.titleLarge) }

        items(trusted, key = { it.deviceId }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.deviceName)
                        Text(device.deviceId, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        // Forget action (Section 9).
                    }) { Text("Forget") }
                }
            }
        }
    }
}
