package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportArtifactStore
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.launchBenchmarkExportDestination
import java.io.File
import java.io.OutputStream

@Composable
internal fun VoiceDiagnosticExportControl(
    content: BenchmarkExportContent,
    label: String,
    tag: String,
    hasMeasurements: Boolean = true,
    write: suspend (OutputStream) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, content) {
        viewModelFactory {
            initializer {
                VoiceDiagnosticExportViewModel(
                    content,
                    BenchmarkExportArtifactStore({ File(context.noBackupFilesDir, "voice-exports/${content.name.lowercase(java.util.Locale.ROOT)}") }),
                    createSavedStateHandle()
                ) { destination -> context.contentResolver.openOutputStream(Uri.parse(destination), "wt") }
            }
        }
    }
    val model: VoiceDiagnosticExportViewModel = viewModel(key = "voice-export-${content.name}", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        model.destinationSelected(destination?.toString())
    }
    LaunchedEffect(state.destinationRequest, state.preparing) {
        val request = state.destinationRequest ?: return@LaunchedEffect
        val artifact = model.takeDestinationRequest(request) ?: return@LaunchedEffect
        launchBenchmarkExportDestination(artifact.fileName, { launcher.launch(it) }) { model.destinationFailed() }
    }
    TextButton(
        onClick = { model.request(write) },
        enabled = state.loaded && !state.busy && !state.choosingDestination && state.destinationRequest == null && (hasMeasurements || state.retained != null),
        modifier = Modifier.testTag(tag)
    ) {
        Text(
            when {
                state.preparing -> "Preparing export…"
                state.saving -> "Saving…"
                state.retained != null -> "Save retained export"
                else -> label
            }
        )
    }
    if (state.retained != null && !state.busy && !state.choosingDestination && state.destinationRequest == null) {
        TextButton(onClick = model::discard) { Text("Discard retained export") }
    }
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
