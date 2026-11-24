package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceSnapshot
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import dev.chungjungsoo.gptmobile.data.voice.writeVoicePerformanceExport
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent

@Composable
internal fun VoicePerformanceExportControl(snapshot: VoicePerformanceSnapshot, latest: VoiceSynthesisFeedback?) {
    Column {
        VoiceDiagnosticExportControl(
            content = BenchmarkExportContent.VOICE_PERFORMANCE,
            label = "Save measurements",
            tag = "voice_export_performance",
            hasMeasurements = snapshot.rows.isNotEmpty() || latest?.generatedAudioMs?.let { it > 0 } == true
        ) { output -> writeVoicePerformanceExport(snapshot, latest, output) }
        Text("Saves timings, actual speech providers and route categories. Conversation text and audio are excluded.", style = MaterialTheme.typography.bodySmall)
    }
}
