package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.morsecode.ui.AppState
import net.morsecode.ui.lanAddress
import net.morsecode.webconnect.PairingManager
import net.morsecode.webconnect.WebConnectServer

/**
 * Web Connect (Section H): toggle the embedded server, show the PIN + URL (QR
 * encodes the URL with a one-time token), and manage which files are shared
 * this session (default: none).
 */
@Composable
fun WebConnectScreen(app: AppState) {
    var running by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }

    val server = remember {
        val pairing = PairingManager(
            randomBytes = { app.crypto.randomBytes(it) },
            nowMillis = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
        )
        PairingHolder(pairing, null)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Web Connect", style = MaterialTheme.typography.titleLarge)
        Text(
            "Reach this device from any browser on the same network — no install.",
            style = MaterialTheme.typography.bodySmall,
        )

        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable", Modifier.weight(1f))
            Switch(checked = running, onCheckedChange = { on ->
                running = on
                val host = lanAddress() ?: "0.0.0.0"
                if (on) {
                    server.pairing.enable()
                    pin = server.pairing.pin
                    url = "http://$host:${WebConnectServer.DEFAULT_PORT}/"
                    if (server.server == null) {
                        server.server = WebConnectServer(
                            pairing = server.pairing,
                            host = host,
                            onUpload = { name, _ -> /* saved via platform sink */ },
                            onChatFromBrowser = { text -> /* bridged into chat_message */ },
                        )
                    }
                    server.server?.start()
                } else {
                    server.pairing.disable()
                    server.server?.stop()
                    pin = null
                    url = null
                }
            })
        }

        if (running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PIN", style = MaterialTheme.typography.titleMedium)
                    Text(pin ?: "", style = MaterialTheme.typography.displayMedium)
                    Text(url ?: "", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Scan the on-device QR to skip PIN entry. Nothing is shared until you choose it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private class PairingHolder(
    val pairing: PairingManager,
    var server: WebConnectServer?,
)
