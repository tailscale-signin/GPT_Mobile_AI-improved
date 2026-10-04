package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.queue.FollowUpPhase
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import kotlinx.coroutines.launch

@Composable
internal fun QueuedPromptBubble(
    prompt: PendingPrompt,
    onEdit: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onPause: (String, Boolean) -> Unit,
    phase: FollowUpPhase? = null
) {
    val merging = phase == FollowUpPhase.MERGING
    val opacity = remember(prompt.id) { Animatable(1f) }
    val lift = remember(prompt.id) { Animatable(0f) }
    val tint = remember(prompt.id) { Animatable(0f) }
    LaunchedEffect(merging) {
        if (merging) {
            tint.animateTo(1f, tween(500))
            opacity.animateTo(0.05f, tween(500))
            opacity.animateTo(1f, tween(500))
            opacity.animateTo(0.05f, tween(500))
            opacity.snapTo(1f)
            launch { opacity.animateTo(0f, tween(650)) }
            lift.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    var editing by remember(prompt.id) { mutableStateOf(false) }
    var text by remember(prompt.id, prompt.text) { mutableStateOf(prompt.text) }
    val attachments = remember(prompt.payload) { runCatching { prompt.details().attachments }.getOrDefault(emptyList()) }
    val preview = listOf(prompt.text, attachments.joinToString(" · ") { it.resolvedDisplayName }).filter { it.isNotBlank() }.joinToString("\n")
    Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = androidx.compose.ui.graphics.lerp(Color.Gray.copy(alpha = 0.18f), MaterialTheme.colorScheme.primaryContainer, tint.value),
            modifier = Modifier.graphicsLayer {
                alpha = opacity.value
                translationY = -size.height * 1.3f * lift.value
                scaleX = 1f - 0.12f * lift.value
                scaleY = 1f - 0.25f * lift.value
            }.clickable(enabled = !merging) { editing = true }
                .semantics { contentDescription = "${if (prompt.paused) "Paused" else "Queued"} draft. Tap to edit or remove." }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when (phase) {
                        FollowUpPhase.SEARCHING -> "Preparing follow-up…"
                        FollowUpPhase.READY -> "Ready to add"
                        FollowUpPhase.MERGING -> "Joining your prompt"
                        null -> if (prompt.paused) "Paused" else "Queued"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    preview.ifBlank { "Attached document" },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (merging) 1f else 0.45f),
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Light
                )
            }
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
                        TextButton(onClick = {
                            onPause(prompt.id, !prompt.paused)
                            editing = false
                        }) { Text(if (prompt.paused) "Resume" else "Pause") }
                        TextButton(onClick = {
                            onRemove(prompt.id)
                            editing = false
                        }) { Text("Remove") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() || attachments.isNotEmpty(), onClick = {
                    onEdit(prompt.id, text)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
