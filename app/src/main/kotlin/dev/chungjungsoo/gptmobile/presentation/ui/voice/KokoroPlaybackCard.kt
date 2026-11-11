package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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

@Composable
internal fun KokoroPlaybackCard(
    state: LocalVoiceModelState,
    voice: KokoroVoice?,
    threads: Int,
    english: Boolean,
    previewError: String?,
    select: (KokoroVoice?) -> Unit,
    selectThreads: (Int) -> Unit,
    install: () -> Unit,
    cancel: () -> Unit,
    remove: () -> Unit,
    preview: (KokoroVoice) -> Unit
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
            Text("Download once to play spoken replies on your phone. If Kokoro is unavailable, Voice uses an installed offline Android voice.", style = MaterialTheme.typography.bodySmall)
            when (state.status) {
                LocalVoiceModelStatus.CHECKING -> Text("Checking for the voice pack…", style = MaterialTheme.typography.bodySmall)
                LocalVoiceModelStatus.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (state.downloadedBytes == state.totalBytes) {
                            "Verifying and preparing the voice pack…"
                        } else {
                            "${state.downloadedBytes / 1_048_576} / $downloadMiB MiB downloaded"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = cancel) { Text("Cancel download") }
                }
                LocalVoiceModelStatus.READY -> Text("Installed", color = MaterialTheme.colorScheme.primary)
                LocalVoiceModelStatus.NOT_INSTALLED, LocalVoiceModelStatus.FAILED -> {
                    Button(onClick = install, modifier = Modifier.testTag("download_kokoro_voice")) { Text("Download voice pack · $downloadMiB MiB") }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!ready && voice != null) {
                Text("${voice.label} selected · Uses Android until the pack is ready", style = MaterialTheme.typography.bodySmall)
            }
            if (ready || voice != null) {
                FilterChip(selected = voice == null, onClick = { select(null) }, label = { Text("Android voice") })
            }
            if (ready) {
                if (!english) Text("Set the speech language to English to use Kokoro.", style = MaterialTheme.typography.bodySmall)
                KokoroVoice.entries.forEach { option ->
                    FilterChip(selected = voice == option, onClick = { select(option) }, label = { Text(option.label) }, enabled = english)
                }
                if (voice != null) {
                    Text("Synthesis threads", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (2..4).forEach { count ->
                            FilterChip(selected = threads == count, onClick = { selectThreads(count) }, label = { Text(count.toString()) })
                        }
                    }
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
