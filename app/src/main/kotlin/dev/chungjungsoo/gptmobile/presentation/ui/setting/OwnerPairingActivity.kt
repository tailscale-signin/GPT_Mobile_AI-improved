package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairingLink
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity

/** A link previews an invitation only. Selecting a profile and requesting pairing require explicit taps. */
@AndroidEntryPoint
class OwnerPairingActivity : ProtectedActivity() {
    private var invitation by mutableStateOf<String?>(null)
    private var invalidInvitation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
        setContent {
            GPTMobileTheme {
                Surface {
                    if (invalidInvitation) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Gateway invitation unavailable", style = MaterialTheme.typography.headlineSmall)
                            Text("Create a current Android control invitation on the PC. This link is invalid or expired; no pairing or settings change was sent.")
                            TextButton(onClick = { invalidInvitation = false }) { Text("Open Gateway settings") }
                            TextButton(onClick = { finish() }) { Text("Close") }
                        }
                    } else {
                        GatewayControlSettingsDialog(onDismiss = { finish() }, initialPairingLink = invitation)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        val raw = intent.dataString
        invitation = raw?.takeIf { runCatching { GatewayPairingLink.parse(it, System.currentTimeMillis() / 1000) }.isSuccess }
        invalidInvitation = raw != null && invitation == null
    }
}
