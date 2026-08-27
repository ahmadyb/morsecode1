package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/**
 * Web Connect (Section H): show the pairing PIN + the LAN URL a browser should
 * open. The embedded Ktor server's lifecycle is owned by the platform entry
 * point (it is JVM/`java.net`-backed); this screen only drives the pure,
 * testable [PairingManager] and presents what the user needs to connect.
 */
@Composable
fun WebConnectScreen(app: AppState) {
    var running by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }

    val pairing = remember {
        PairingManager(
            randomBytes = { app.crypto.randomBytes(it) },
            nowMillis = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Web Connect", style = MaterialTheme.typography.titleLarge)
        Text(
            "Reach this device from any browser on the same network — no install.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable", Modifier.weight(1f))
            Switch(checked = running, onCheckedChange = { on ->
                running = on
                if (on) {
                    pairing.enable()
                    pin = pairing.pin
                    val host = lanAddress() ?: "this-device"
                    url = "http://$host:8080/"
                } else {
                    pairing.disable()
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
