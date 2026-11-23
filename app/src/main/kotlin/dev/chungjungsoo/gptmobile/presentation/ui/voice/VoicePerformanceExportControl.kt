package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceSnapshot
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import dev.chungjungsoo.gptmobile.data.voice.voicePerformanceExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoicePerformanceExportControl(snapshot: VoicePerformanceSnapshot, latest: VoiceSynthesisFeedback?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captured by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        val content = captured
        captured = null
        if (destination != null && content != null) {
            writing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination)?.bufferedWriter()?.use { it.write(content) }
                            ?: error("The selected destination could not be opened.")
                    }
                    notice = "Voice measurements saved."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = "Measurements could not be saved. Choose another destination and try again."
                } finally {
                    writing = false
                }
            }
        }
    }
    Column {
        TextButton(
            onClick = {
                // Freeze this observation before the document picker pauses the screen.
                captured = voicePerformanceExport(snapshot, latest)
                notice = null
                export.launch("voice-performance-v3.json")
            },
            enabled = !writing && captured == null && (snapshot.rows.isNotEmpty() || latest?.generatedAudioMs?.let { it > 0 } == true),
            modifier = Modifier.testTag("voice_export_performance")
        ) { Text(if (writing) "Saving measurements…" else "Save measurements") }
        Text("Saves timings, actual speech providers and route categories. Conversation text and audio are excluded.", style = MaterialTheme.typography.bodySmall)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
