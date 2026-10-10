package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.queue.FollowUpInbox
import dev.chungjungsoo.gptmobile.data.queue.FollowUpPhase
import dev.chungjungsoo.gptmobile.data.queue.FollowUpProgress
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.coroutines.delay

@Composable
internal fun QueuedPromptBubble(
    prompt: PendingPrompt,
    onEdit: (String, String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onPause: (String, Boolean) -> Unit,
    progress: FollowUpProgress? = null
) {
    val merging = progress?.phase == FollowUpPhase.MERGING
    val deadline = progress?.deadlineMs
    var remaining by remember(prompt.id, deadline) { mutableLongStateOf((deadline?.minus(System.nanoTime() / 1_000_000L) ?: 0L).coerceAtLeast(0L)) }
    var editing by remember(prompt.id) { mutableStateOf(false) }
    var text by remember(prompt.id, prompt.text) { mutableStateOf(prompt.text) }
    val canChange = !merging && (deadline == null || remaining > 0L)
    val opacity = remember(prompt.id) { Animatable(1f) }
    LaunchedEffect(deadline) {
        if (deadline != null) {
            do {
                remaining = (deadline - System.nanoTime() / 1_000_000L).coerceAtLeast(0L)
                if (remaining > 0L) delay(minOf(remaining, 32L))
            } while (remaining > 0L)
        }
    }
    LaunchedEffect(canChange) { if (!canChange) editing = false }
    LaunchedEffect(merging) { if (merging) opacity.animateTo(0f, tween(400)) }
    val attachments = remember(prompt.payload) { runCatching { prompt.details().attachments }.getOrDefault(emptyList()) }
    val preview = listOf(prompt.text, attachments.joinToString(" · ") { it.resolvedDisplayName }).filter { it.isNotBlank() }.joinToString("\n")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).graphicsLayer { alpha = opacity.value },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(enabled = canChange, onClick = { onRemove(prompt.id) }) {
            Icon(Icons.Rounded.Stop, contentDescription = "Stop queued message")
        }
        if (prompt.paused) {
            IconButton(onClick = { onPause(prompt.id, false) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume queued message")
            }
        }
        IconButton(enabled = canChange, onClick = { editing = true }) {
            Icon(Icons.Rounded.Edit, contentDescription = "Edit queued message")
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        merging -> "Added to original question"
                        deadline != null && remaining == 0L -> "Adding to original question…"
                        deadline != null -> "Adding in ${(remaining + 999L) / 1000L}s"
                        prompt.paused -> "Paused"
                        else -> "Queued"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(preview.ifBlank { "Attached document" }, style = MaterialTheme.typography.bodyLarge)
                if (prompt.paused) {
                    Text(
                        "Paused. Check the selected profile and spend settings, then resume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (deadline != null) {
                    LinearProgressIndicator(
                        progress = { 1f - remaining.toFloat() / FollowUpInbox.GRACE_MS },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    if (editing && canChange) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit queued message") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message") })
                    if (deadline != null) {
                        Text("Save within ${(remaining + 999L) / 1000L}s to include your changes.", style = MaterialTheme.typography.labelSmall)
                    } else {
                        TextButton(onClick = {
                            onPause(prompt.id, !prompt.paused)
                            editing = false
                        }) {
                            Text(if (prompt.paused) "Resume" else "Pause")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = canChange && (text.isNotBlank() || attachments.isNotEmpty()), onClick = {
                    onEdit(prompt.id, text, attachments.isNotEmpty())
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
