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
    recheckBackend: () -> Unit,
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
        VoiceChoice("Speech Gateway", value.gatewayUid, value.gateways.map { it.uid to it.name }, selectGateway, enabled = enabled && value.loaded)
        Text("Gateway speech sends reply text/tokens to your selected PC.", style = MaterialTheme.typography.bodySmall)
        if (value.gateways.isEmpty()) Text("Add a Gateway connection in Settings to use PC speech.", style = MaterialTheme.typography.bodySmall)
    } else if (value.backend.isStreamingLocal) {
        Text("These engines stream audio locally. Each model has its own voice quality and speed; try a preview on this phone.", style = MaterialTheme.typography.bodySmall)
        if (value.backend == VoiceSpeechBackend.LITERT_KOKORO_SM8750) {
            Text("This pack uses a vocoder compiled for Snapdragon 8 Elite and the Kokoro CPU acoustic model. The benchmark measures delivered audio and reports SDK placement as unobserved.", style = MaterialTheme.typography.bodySmall)
        } else if (value.backend in setOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED)) {
            Text("Kokoro requests the GPU for its vocoder. The runtime can also use CPU and does not report processor placement.", style = MaterialTheme.typography.bodySmall)
        } else if (value.backend == VoiceSpeechBackend.LITERT_KOKORO_NPU) {
            Text("The LiteRT pack failed a Snapdragon 8 Elite compiler check. This route may use CPU. For verified NPU playback, select Hexagon NPU and run Recheck.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        if (value.backend == VoiceSpeechBackend.AUTO) {
            Text("Auto uses saved measurements from this phone. Compare engines to time CPU, GPU and NPU before the next reply.", style = MaterialTheme.typography.bodySmall)
        } else if (value.backend != VoiceSpeechBackend.CPU) {
            Text("This accelerator is experimental. Supported graph sections can run alongside CPU fallback. Audio must pass the CPU comparison before playback; failed qualification uses CPU for the unplayed phrase.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = recheckBackend, enabled = enabled && value.loaded) {
            Text(if (value.backend == VoiceSpeechBackend.AUTO) "Compare engines on next reply" else "Recheck engine on next reply")
        }
    }
    Text(value.status, Modifier.testTag("voice_backend_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
