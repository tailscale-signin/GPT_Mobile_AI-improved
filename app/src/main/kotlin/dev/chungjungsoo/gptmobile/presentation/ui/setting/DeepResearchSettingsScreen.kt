package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeepResearchSettingsScreen(onClose: () -> Unit, viewModel: DeepResearchSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.open() }
    val cancel = { if (!state.saving) { viewModel.discard(); onClose() } }
    FadingDialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().testTag("deep-research-settings"), topBar = {
            TopAppBar(title = { Text("Deep Research") }, navigationIcon = { TextButton(onClick = cancel, enabled = !state.saving) { Text("Cancel") } }, actions = {
                TextButton(onClick = { viewModel.save(onClose) }, enabled = state.draft != null && !state.saving, modifier = Modifier.testTag("research-save")) { Text(if (state.saving) "Saving…" else "Save") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Global defaults · Changes apply to future runs", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("research-save-error")) }
                state.draft?.let { DeepResearchSettingsCard(it, !state.saving, viewModel::edit) }
                state.storage?.let { storage ->
                    Text("Storage & resumable runs", style = MaterialTheme.typography.titleMedium)
                    Text("${storage.retainedSources} retained source records · ${storage.bytes / 1024} KiB used")
                    TextButton(onClick = viewModel::refreshStorage) { Text("Refresh diagnostics") }
                    storage.resumable.take(8).forEach { session ->
                        Text("${session.snapshot.phase} · ${session.snapshot.sources.count { it.readable }}/${session.snapshot.targetSources} readable sources")
                        TextButton(onClick = { viewModel.migrate(session) }) { Text("Remove legacy cap for next resume") }
                    }
                    Text("Migration preserves spent counters and evidence. Runs start only from the conversation; completed history is never restarted.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
