package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine

@Composable
internal fun StreamingVoiceModelCard(
    engine: MediaPipeSpeechEngine,
    state: LocalVoiceModelState?,
    voice: KokoroVoice?,
    enabled: Boolean,
    selectVoice: (KokoroVoice) -> Unit,
    install: () -> Unit,
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: () -> Unit,
    previewError: String?,
    setup: Boolean = false
) {
    val model = MediaPipeSpeechCatalog.model(engine)
    val current = state ?: LocalVoiceModelState(totalBytes = model.downloadBytes)
    val downloadMiB = (model.downloadBytes + 524_288) / 1_048_576
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("streaming_voice_model"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${model.title} · Offline English speech", style = MaterialTheme.typography.titleMedium)
            Text("Starts playing audio as the engine produces it. Word highlighting uses estimated timing.", style = MaterialTheme.typography.bodySmall)
            when (current.status) {
                LocalVoiceModelStatus.CHECKING -> Text("Checking the voice pack…")
                LocalVoiceModelStatus.DOWNLOADING -> {
                    VoiceTransferProgress(current.transferStage, current.downloadedBytes, current.totalBytes)
                    Text("${current.downloadedBytes / 1_048_576} / $downloadMiB MiB downloaded", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = cancel) { Text("Cancel download") }
                }
                LocalVoiceModelStatus.READY -> Text("Installed and verified", color = MaterialTheme.colorScheme.primary)
                LocalVoiceModelStatus.NOT_INSTALLED, LocalVoiceModelStatus.FAILED -> {
                    Button(onClick = install, enabled = enabled, modifier = Modifier.testTag("download_streaming_voice")) {
                        Text("Download voice pack · $downloadMiB MiB")
                    }
                }
            }
            current.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (current.status == LocalVoiceModelStatus.READY && !setup) {
                if (engine == MediaPipeSpeechEngine.KOKORO) {
                    KokoroVoice.entries.forEach { option ->
                        FilterChip(selected = voice == option, onClick = { selectVoice(option) }, label = { Text(option.label) }, enabled = enabled)
                    }
                } else {
                    Text("Voice · ${model.defaultVoice.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = preview, enabled = enabled) { Text("Preview streaming voice") }
                    TextButton(onClick = remove, enabled = enabled) { Text("Remove pack") }
                }
            }
            Text("The engine manages its CPU threads. The native Kokoro thread setting does not apply.", style = MaterialTheme.typography.bodySmall)
            Text("${model.title} model · ${model.license}", style = MaterialTheme.typography.bodySmall)
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
