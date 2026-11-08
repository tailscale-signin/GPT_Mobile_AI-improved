package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

/** Raw durable parts are paged independently of the normal chat projection. */
@Composable
fun GatewayTranscriptDialog(conversationKey: String, onDismiss: () -> Unit, model: GatewayTranscriptViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(conversationKey) { model.open(conversationKey) }
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.conversation?.title ?: "Retained gateway transcript", style = MaterialTheme.typography.titleMedium)
                Text("Read only · ${state.conversation?.profileName?.ifBlank { "Original profile not recorded" } ?: "Loading"}", style = MaterialTheme.typography.bodySmall)
                if (state.conversation?.historyComplete != true) Text(state.conversation?.historyLimitation ?: "Earlier history has not been verified.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    TextButton(onClick = { model.reconnect() }, enabled = !state.loading) { Text("Reconnect") }
                    TextButton(onClick = model::previous, enabled = state.hasPrevious && !state.loading) { Text("Previous parts") }
                    TextButton(onClick = model::next, enabled = state.hasNext && !state.loading) { Text("Next parts") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.rows, key = { it.messageId }) { row ->
                        var slice by remember(row.messageId) { mutableIntStateOf(0) }
                        val count = ((row.content.length + 8191) / 8192).coerceAtLeast(1)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${row.role} · ${row.kind} · message ${row.logicalMessageId}", style = MaterialTheme.typography.labelMedium)
                            val retained = state.partsRetained[row.logicalMessageId] ?: 0
                            Text("Part ${row.partIndex + 1}/${row.partCount} · $retained/${row.partCount} retained · ${if (row.contentVerified) "complete digest verified" else "incomplete or not yet verified"}", style = MaterialTheme.typography.bodySmall)
                            SelectionContainer {
                                Text(row.content.substring((slice * 8192).coerceAtMost(row.content.length), ((slice + 1) * 8192).coerceAtMost(row.content.length)), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (count > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { slice-- }, enabled = slice > 0) { Text("Previous text") }
                                    Text("${slice + 1}/$count", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { slice++ }, enabled = slice + 1 < count) { Text("Next text") }
                                }
                            }
                        }
                    }
                    if (state.rows.isEmpty()) item { Text(if (state.loading) "Reading retained messages…" else "No message parts are cached. Reconnect reads the saved transcript without resubmitting work.") }
                }
            }
        }
    }
}
