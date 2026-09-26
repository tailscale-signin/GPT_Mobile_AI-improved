package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptQueuePanel(
    entries: List<PendingPrompt>,
    onEdit: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onPause: (String, Boolean) -> Unit,
    onMove: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PendingPrompt?>(null) }
    var draft by remember { mutableStateOf("") }
    if (entries.isEmpty()) return
    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.queue_manage, entries.size)) }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(entries, key = { _, prompt -> prompt.id }) { index, prompt ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(prompt.text.ifBlank { stringResource(R.string.queue_attachment) })
                            Text(stringResource(if (prompt.paused) R.string.queue_paused else R.string.queue_waiting))
                            Row {
                                TextButton(onClick = {
                                    editing = prompt
                                    draft = prompt.text
                                }) { Text(stringResource(R.string.queue_edit)) }
                                TextButton(onClick = { onPause(prompt.id, !prompt.paused) }) {
                                    Text(stringResource(if (prompt.paused) R.string.queue_resume else R.string.queue_pause))
                                }
                                TextButton(onClick = { onRemove(prompt.id) }) { Text(stringResource(R.string.queue_remove)) }
                            }
                            Row {
                                TextButton(enabled = index > 0, onClick = { onMove(prompt.id, entries[index - 1].id) }) { Text(stringResource(R.string.queue_earlier)) }
                                TextButton(enabled = index < entries.lastIndex, onClick = { onMove(prompt.id, entries[index + 1].id) }) { Text(stringResource(R.string.queue_later)) }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { prompt ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.queue_edit)) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(stringResource(R.string.queue_prompt)) }) },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    onEdit(prompt.id, draft)
                    editing = null
                }) { Text(stringResource(R.string.queue_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.queue_cancel)) } }
        )
    }
}
