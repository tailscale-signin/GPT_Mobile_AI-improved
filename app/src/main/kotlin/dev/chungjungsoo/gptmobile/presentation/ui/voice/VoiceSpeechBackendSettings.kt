package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend

@Composable
internal fun VoiceSpeechBackendSettings(
    value: VoiceBackendSettings,
    selectBackend: (VoiceSpeechBackend) -> Unit,
    selectGateway: (String) -> Unit,
    enabled: Boolean
) {
    VoiceSettingsSection("Speech processing")
    VoiceChoice(
        "Speech engine",
        value.backend.name,
        VoiceSpeechBackend.entries.map { it.name to it.label },
        { selectBackend(VoiceSpeechBackend.valueOf(it)) },
        enabled = enabled && value.loaded
    )
    if (value.backend == VoiceSpeechBackend.GATEWAY_CUDA) {
        VoiceChoice("Speech Gateway", value.gatewayUid.orEmpty(), value.gateways.map { it.uid to it.name }, selectGateway, enabled = enabled && value.loaded)
        Text("Gateway speech sends reply text/tokens to your selected PC.", style = MaterialTheme.typography.bodySmall)
        if (value.gateways.isEmpty()) Text("Add a Gateway connection in Settings to use PC speech.", style = MaterialTheme.typography.bodySmall)
    } else if (value.backend != VoiceSpeechBackend.CPU) {
        Text("Experimental hardware path. Availability depends on the installed model, runtime and this device; selection alone does not verify acceleration.", style = MaterialTheme.typography.bodySmall)
    }
    Text(value.status, Modifier.testTag("voice_backend_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
