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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.PocketCaptionModelStore
import dev.chungjungsoo.gptmobile.data.voice.PocketCompiledSpeechCatalog

@Composable
internal fun PocketCompiledModelCard(
    state: LocalVoiceModelState?,
    captionState: LocalVoiceModelState?,
    voiceId: String?,
    selectVoice: (String) -> Unit,
    enabled: Boolean,
    install: () -> Unit,
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: () -> Unit = {},
    previewError: String? = null,
    setup: Boolean = false
) {
    val current = state ?: LocalVoiceModelState(totalBytes = PocketCompiledSpeechCatalog.TOTAL_BYTES)
    val caption = captionState ?: LocalVoiceModelState(totalBytes = PocketCaptionModelStore.TOTAL_BYTES)
    val speechReady = current.status == LocalVoiceModelStatus.READY
    val captionReady = caption.status == LocalVoiceModelStatus.READY
    val uriHandler = LocalUriHandler.current
    val selected = voiceId?.takeIf { it in PocketCompiledSpeechCatalog.VOICES } ?: "alba"
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("pocket_compiled_model"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pocket · GPU / Qualcomm NPU voice pack", style = MaterialTheme.typography.titleMedium)
            Text("English streaming speech · experimental. The GPU and Qualcomm NPU choices share this pack. A preview checks whether the selected accelerator can open it on this phone.", style = MaterialTheme.typography.bodySmall)
            VoiceChoice(
                "Streaming voice",
                selected,
                PocketCompiledSpeechCatalog.VOICES.map { it to it.replaceFirstChar(Char::uppercaseChar) },
                selectVoice,
                enabled = enabled
            )
            MediaPipeSpeechVoices.attribution(selected)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                current.status == LocalVoiceModelStatus.DOWNLOADING || caption.status == LocalVoiceModelStatus.DOWNLOADING -> {
                    val transfer = if (current.status == LocalVoiceModelStatus.DOWNLOADING) current else caption
                    Text(if (current.status == LocalVoiceModelStatus.DOWNLOADING) "Downloading voice pack…" else "Preparing local caption timing…")
                    VoiceTransferProgress(transfer.transferStage, transfer.downloadedBytes, transfer.totalBytes)
                    TextButton(onClick = cancel) { Text("Cancel download") }
                }
                current.status == LocalVoiceModelStatus.CHECKING || caption.status == LocalVoiceModelStatus.CHECKING -> Text("Checking voice and caption packs…")
                speechReady && captionReady -> Text("Voice and caption packs installed and verified", color = MaterialTheme.colorScheme.primary)
                else -> Button(onClick = install, enabled = enabled, modifier = Modifier.testTag("download_pocket_compiled")) {
                    val bytes = (if (speechReady) 0L else PocketCompiledSpeechCatalog.TOTAL_BYTES) + (if (captionReady) 0L else PocketCaptionModelStore.TOTAL_BYTES)
                    val label = when {
                        speechReady -> "Download caption timing"
                        captionReady -> "Download voice pack"
                        else -> "Download voice and caption packs"
                    }
                    Text("$label · up to ${(bytes + 524_288) / 1_048_576} MiB")
                }
            }
            current.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            caption.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if ((speechReady || captionReady) && !setup) {
                Row {
                    if (speechReady) TextButton(onClick = preview, enabled = enabled) { Text("Preview streaming voice") }
                    TextButton(onClick = remove, enabled = enabled) { Text("Remove voice and caption packs") }
                }
            }
            Text("Live captions align to each short phrase's generated audio on this phone before playback starts. This adds a short delay; words the aligner cannot match remain readable without a timed highlight. Existing local recognition files are reused when available.", style = MaterialTheme.typography.bodySmall)
            if (speechReady && !captionReady) Text("Speech is available. Download caption timing to enable word highlights.", style = MaterialTheme.typography.bodySmall)
            Text("Successful audio does not confirm which processor ran each graph.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri("https://huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT") }) { Text("Model source and voice terms") }
            previewError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
