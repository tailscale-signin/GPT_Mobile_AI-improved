package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pairing.OwnerGateway
import dev.chungjungsoo.gptmobile.data.pairing.OwnerInvitation
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OwnerPairingActivity : ProtectedActivity() {
    @Inject lateinit var settings: SettingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GPTMobileTheme {
                val scope = rememberCoroutineScope()
                var link by remember { mutableStateOf(intent.dataString.orEmpty()) }
                val invite = remember(link) { runCatching { OwnerInvitation.parse(link.trim()) }.getOrNull() }
                var name by remember { mutableStateOf("Windows Gateway") }
                var model by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var devices by remember { mutableStateOf(OwnerGateway.pairedDevices()) }
                        devices.forEach { device -> OwnerGatewayControls(device, device.origin) }
                        Text("Pair a Windows Gateway", style = MaterialTheme.typography.headlineSmall)
                        Text("On your PC, open Android controls and create an invitation. Scan its QR code or paste the invitation here.")
                        OutlinedTextField(link, { link = it.take(2048) }, enabled = !busy, singleLine = true, label = { Text("Invitation link") }, modifier = Modifier.fillMaxWidth())
                        invite?.let { Text("PC  ${it.origin}\nCertificate fingerprint  ${it.pin.chunked(8).joinToString(" ")}") }
                        OutlinedTextField(name, { name = it.take(80) }, enabled = !busy, label = { Text("Gateway name") })
                        OutlinedTextField(model, { model = it.take(200) }, enabled = !busy, label = { Text("Local model ID (optional for settings)") })
                        Button(enabled = invite != null && name.isNotBlank() && !busy, onClick = {
                            val target = invite ?: return@Button
                            busy = true
                            scope.launch {
                                try {
                                    val existing = OwnerGateway.find(target.origin)
                                    if (existing != null) check(existing.pin == target.pin) { "The PC certificate changed. Review the saved pairing." }
                                    val entry = existing ?: OwnerGateway.pair(target)
                                    val endpoint = entry.origin + "/v1"
                                    if (entry.role == "owner" && model.isNotBlank() && settings.fetchPlatformV2s().none { it.apiUrl == endpoint && it.compatibleType == ClientType.LLAMA }) {
                                        settings.addPlatformV2(PlatformV2(name = name.trim(), compatibleType = ClientType.LLAMA, apiUrl = endpoint, model = model.trim(), enabled = true))
                                    }
                                    devices = OwnerGateway.pairedDevices()
                                    message = if (entry.role == "owner" && model.isNotBlank()) "Paired. Select this connection under GitHub settings → Engineering gateway." else "Paired as ${entry.role}. Gateway settings are available above."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    message = failure.message ?: "Pairing failed. Check the invitation and PC listener."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(if (busy) "Pairing…" else "Pair and save connection") }
                        if (message.isNotEmpty()) Text(message)
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
