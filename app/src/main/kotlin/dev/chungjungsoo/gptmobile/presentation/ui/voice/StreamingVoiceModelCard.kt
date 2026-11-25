package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.chungjungsoo.gptmobile.data.voice.isSm8750SpeechDevice

@Composable
internal fun StreamingVoiceModelCard(
    engine: MediaPipeSpeechEngine,
    state: LocalVoiceModelState?,
    enabled: Boolean,
    install: () -> Unit,
    importCompiled: (Uri) -> Unit = {},
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: () -> Unit,
    previewError: String?,
    standardPack: LocalVoiceModelState? = null,
    preparingNpuBase: Boolean = false,
    npuBaseError: String? = null,
    setup: Boolean = false
) {
    val model = MediaPipeSpeechCatalog.model(engine)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCompiled(uri)
    }
    val current = state ?: LocalVoiceModelState(totalBytes = model.downloadBytes)
    val standardReady = standardPack?.status == LocalVoiceModelStatus.READY
    val gpuPack = engine == MediaPipeSpeechEngine.KOKORO_GPU
    val downloadMiB = (model.downloadBytes + 524_288) / 1_048_576
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("streaming_voice_model"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${model.title} · Offline speech", style = MaterialTheme.typography.titleMedium)
            Text("Starts playing audio as the engine produces it. Word highlighting uses estimated timing.", style = MaterialTheme.typography.bodySmall)
            when {
                model.requiresSm8750 && !isSm8750SpeechDevice() -> Text("This vocoder requires a Snapdragon 8 Elite (SM8750).")
                current.status == LocalVoiceModelStatus.READY -> Text("Installed and verified", color = MaterialTheme.colorScheme.primary)
                model.requiresSm8750 && preparingNpuBase -> {
                    Text("Preparing standard Kokoro for the NPU pack…")
                    if (standardPack?.status == LocalVoiceModelStatus.DOWNLOADING) {
                        VoiceTransferProgress(standardPack.transferStage, standardPack.downloadedBytes, standardPack.totalBytes)
                    }
                    TextButton(onClick = cancel) { Text("Cancel preparation") }
                }
                current.status == LocalVoiceModelStatus.CHECKING -> Text("Checking the voice pack…")
                current.status == LocalVoiceModelStatus.DOWNLOADING -> {
                    val variant = model.requiresSm8750 || gpuPack
                    VoiceTransferProgress(
                        current.transferStage,
                        current.downloadedBytes,
                        current.totalBytes,
                        label = if (variant) "Installing the voice pack…" else voiceTransferLabel(current.transferStage)
                    )
                    Text("${current.downloadedBytes / 1_048_576} / $downloadMiB MiB", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = cancel) { Text(if (variant) "Cancel installation" else "Cancel download") }
                }
                else -> {
                    Button(onClick = install, enabled = enabled, modifier = Modifier.testTag("download_streaming_voice")) {
                        Text(
                            when {
                                model.requiresSm8750 && standardReady -> "Prepare NPU pack offline"
                                model.requiresSm8750 -> "Get standard Kokoro and prepare NPU pack"
                                gpuPack && standardReady -> "Prepare GPU pack offline"
                                gpuPack -> "Download GPU pack · $downloadMiB MiB"
                                else -> "Download voice pack · $downloadMiB MiB"
                            }
                        )
                    }
                    if (model.requiresSm8750) {
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("Import verified SM8750 pack") }
                        npuBaseError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
