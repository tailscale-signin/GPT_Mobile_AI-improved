package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.data.voice.VoiceTransferStage

internal fun voiceTransferLabel(stage: VoiceTransferStage?): String = when (stage) {
    VoiceTransferStage.CONNECTING -> "Connecting to the voice download…"
    VoiceTransferStage.TRANSFERRING -> "Downloading voice files…"
    VoiceTransferStage.VERIFYING -> "Verifying voice files…"
    VoiceTransferStage.EXTRACTING -> "Preparing the downloaded voice pack…"
    VoiceTransferStage.READY -> "Voice files ready"
    VoiceTransferStage.FAILED -> "Voice download needs attention"
    VoiceTransferStage.CHECKING -> "Checking voice files…"
    null -> "Preparing the voice download…"
}

@Composable
internal fun VoiceTransferProgress(stage: VoiceTransferStage?, downloaded: Long, total: Long) {
    Text(voiceTransferLabel(stage))
    if (stage == VoiceTransferStage.TRANSFERRING && total > 0) {
        LinearProgressIndicator(progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
