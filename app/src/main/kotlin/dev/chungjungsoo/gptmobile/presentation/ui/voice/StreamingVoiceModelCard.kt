package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine

@Composable
internal fun StreamingVoiceModelCard(
    engine: MediaPipeSpeechEngine,
    state: LocalVoiceModelState?,
    enabled: Boolean,
    install: () -> Unit,
    importCompiled: (android.net.Uri) -> Unit = {},
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: () -> Unit,
    previewError: String?,
    setup: Boolean = false
) {
    val model = MediaPipeSpeechCatalog.model(engine)
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCompiled(uri)
    }
    val current = state ?: LocalVoiceModelState(totalBytes = model.downloadBytes)
    val downloadMiB = (model.downloadBytes + 524_288) / 1_048_576
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("streaming_voice_model"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${model.title} · Offline speech", style = MaterialTheme.typography.titleMedium)
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
                    Button(onClick = { if (model.importOnly) picker.launch(arrayOf("*/*")) else install() }, enabled = enabled && (!model.importOnly || dev.chungjungsoo.gptmobile.data.voice.isSm8750SpeechDevice()), modifier = Modifier.testTag("download_streaming_voice")) {
                        Text(if (model.importOnly) "Import verified SM8750 pack" else "Download voice pack · $downloadMiB MiB")
                    }
                }
            }
            current.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (current.status == LocalVoiceModelStatus.READY && !setup) {
                if (!engine.isKokoro) {
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
