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
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy

@Composable
internal fun KokoroPlaybackCard(
    state: LocalVoiceModelState,
    voice: KokoroVoice?,
    threads: Int,
    english: Boolean,
    previewError: String?,
    selectThreads: (Int) -> Unit,
    install: () -> Unit,
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: (KokoroVoice) -> Unit,
    setup: Boolean = false,
    showThreads: Boolean = true
) {
    val ready = state.status == LocalVoiceModelStatus.READY
    val downloadMiB = (state.totalBytes + 524_288) / 1_048_576
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("kokoro_playback"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kokoro · Offline English voices", style = MaterialTheme.typography.titleMedium)
            Text("Play spoken replies on your phone.", style = MaterialTheme.typography.bodySmall)
            when (state.status) {
                LocalVoiceModelStatus.CHECKING -> Text("Checking for the voice pack…", style = MaterialTheme.typography.bodySmall)
                LocalVoiceModelStatus.DOWNLOADING -> {
                    VoiceTransferProgress(state.transferStage, state.downloadedBytes, state.totalBytes)
                    Text("${state.downloadedBytes / 1_048_576} / $downloadMiB MiB downloaded", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = cancel) { Text("Cancel download") }
                }
                LocalVoiceModelStatus.READY -> Text("Installed", color = MaterialTheme.colorScheme.primary)
                LocalVoiceModelStatus.NOT_INSTALLED, LocalVoiceModelStatus.FAILED -> {
                    Button(onClick = install, modifier = Modifier.testTag("download_kokoro_voice")) { Text("Download voice pack · $downloadMiB MiB") }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (ready && !setup) {
                if (!english) Text("Set the speech language to English to use Kokoro.", style = MaterialTheme.typography.bodySmall)
                if (voice != null && showThreads) {
                    VoiceChoice(
                        "Native CPU threads",
                        threads.toString(),
                        listOf("0" to "Auto · calibrated") + (1..VoiceCpuSchedulingPolicy.deviceLimit()).map { it.toString() to it.toString() },
                        { selectThreads(it.toInt()) }
                    )
                }
                Row {
                    voice?.let { selected ->
                        TextButton(onClick = { preview(selected) }, enabled = english) { Text("Preview voice") }
                    }
                    TextButton(onClick = remove) { Text("Remove pack") }
                }
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
