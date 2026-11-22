package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    } else {
        if (value.backend == VoiceSpeechBackend.AUTO) {
            Text("Auto compares complete speech preparation with CPU. It selects GPU or NPU only after valid audio, matching captions and real accelerator execution are measured.", style = MaterialTheme.typography.bodySmall)
        } else if (value.backend != VoiceSpeechBackend.CPU) {
            Text("Supported graph sections run on the selected accelerator; other sections can use CPU. Failed qualification uses CPU for the unplayed phrase.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { selectBackend(value.backend) }, enabled = enabled && value.loaded) {
            Text(if (value.backend == VoiceSpeechBackend.AUTO) "Compare engines on next reply" else "Recheck engine on next reply")
        }
    }
    Text(value.status, Modifier.testTag("voice_backend_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
