package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus

@Composable
internal fun LocalVoiceSetupCard(
    state: LocalVoiceModelState,
    install: () -> Unit,
    cancel: () -> Unit,
    remove: () -> Unit,
    showRemove: Boolean = false,
    compact: Boolean = false
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("local_voice_setup"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Speech on this phone", style = MaterialTheme.typography.titleMedium)
            Text("Moonshine Tiny Streaming · English", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (!compact) Text("Recognize English speech on this phone.", style = MaterialTheme.typography.bodySmall)
            when (state.status) {
                LocalVoiceModelStatus.CHECKING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Checking installed model…", style = MaterialTheme.typography.bodySmall)
                }
                LocalVoiceModelStatus.DOWNLOADING -> {
                    VoiceTransferProgress(state.transferStage, state.downloadedBytes, state.totalBytes)
                    Text("${state.downloadedBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = cancel) { Text("Cancel download") }
                }
                LocalVoiceModelStatus.READY -> {
                    Text("Installed · Recognition works offline", color = MaterialTheme.colorScheme.primary)
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (showRemove) TextButton(onClick = remove) { Text("Remove speech model") }
                }
                LocalVoiceModelStatus.NOT_INSTALLED, LocalVoiceModelStatus.FAILED -> {
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = install, modifier = Modifier.testTag("download_local_voice")) { Text("Download model · ${state.totalBytes / 1_000_000} MB") }
                }
            }
            if (!compact) {
                Row {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/moonshine-ai/moonshine"))) }
                    }) { Text("GitHub project") }
                }
            }
        }
    }
}
