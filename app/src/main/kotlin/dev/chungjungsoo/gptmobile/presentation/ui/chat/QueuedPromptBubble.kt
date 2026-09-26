package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt

@Composable
internal fun QueuedPromptBubble(
    prompt: PendingPrompt,
    onEdit: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onPause: (String, Boolean) -> Unit
) {
    var editing by remember(prompt.id) { mutableStateOf(false) }
    var text by remember(prompt.id, prompt.text) { mutableStateOf(prompt.text) }
    Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.alpha(0.3f).clickable { editing = true }
                .semantics { contentDescription = "${if (prompt.paused) "Paused" else "Queued"} draft. Tap to edit or remove." }
        ) {
            Text(
                prompt.text.ifBlank { "Attached document" },
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Light
            )
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit draft") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message") })
                    Row {
                        TextButton(onClick = { onPause(prompt.id, !prompt.paused); editing = false }) { Text(if (prompt.paused) "Resume" else "Pause") }
                        TextButton(onClick = { onRemove(prompt.id); editing = false }) { Text("Remove") }
                    }
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onEdit(prompt.id, text); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
