package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTimingProvenance
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTranscript
import java.util.Locale

/** Displays the source-audio timeline; it never drives the independent reply-playback clock. */
@Composable
internal fun VoiceRecognitionTimingsDialog(transcript: VoiceRecognitionTranscript, dismiss: () -> Unit) {
    val tokenStarts = transcript.timings.any { it.provenance == VoiceRecognitionTimingProvenance.TOKEN_EMISSION }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Recognition timing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (tokenStarts) {
                        "Times mark token emissions in the input recording. This model does not supply word endings."
                    } else {
                        "Word times are estimated by the recognizer in the input recording. They are separate from reply playback."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("voice_recognition_timings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(transcript.timings) { index, timing ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(transcript.text.substring(timing.startChar, timing.endCharExclusive), Modifier.weight(1f))
                            val start = String.format(Locale.ROOT, "%.2f s", timing.startFrame.toDouble() / transcript.sampleRate)
                            val end = timing.endFrame?.let { String.format(Locale.ROOT, "%.2f s", it.toDouble() / transcript.sampleRate) }
                            Text(if (end == null) start else "$start – $end", Modifier.testTag("voice_recognition_time_$index"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}
