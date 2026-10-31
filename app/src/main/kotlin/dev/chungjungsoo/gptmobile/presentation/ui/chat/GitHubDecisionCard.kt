package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubChatPresence

@Composable
internal fun GitHubDecisionCard(decision: GitHubChatDecision, waiting: Boolean, onAnswer: (Int?, String?) -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    var custom by rememberSaveable(decision.id) { mutableStateOf(false) }
    var answer by rememberSaveable(decision.id) { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("github_decision_${decision.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f))
    ) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.primaryContainer, colors.tertiaryContainer))).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.onPrimaryContainer)
            Column {
                Text("Your direction", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                Text(if (decision.answer == null) "Choose the next step for this feature" else "Your choice is saved", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(decision.question, style = MaterialTheme.typography.titleMedium)
            if (decision.context.isNotBlank()) Text(decision.context, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (decision.answer == null) {
                decision.options.forEachIndexed { index, option ->
                    OutlinedButton(onClick = { onAnswer(index, null) }, modifier = Modifier.fillMaxWidth().testTag("github_decision_option_$index"), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(option.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (decision.recommended == index) {
                                    Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                        Text("Suggested", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (option.detail.isNotBlank()) Text(option.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { custom = !custom }) { Text(if (custom) "Hide custom answer" else "Write my own answer") }
                if (custom) {
                    OutlinedTextField(value = answer, onValueChange = { answer = it.take(2000) }, modifier = Modifier.fillMaxWidth().testTag("github_decision_custom"), label = { Text("Your preference") }, minLines = 2, maxLines = 5, supportingText = { Text("${answer.length}/2000") })
                    Button(onClick = { onAnswer(null, answer) }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use my answer") }
                }
                Text(if (waiting) "Work that depends on this choice is waiting for you." else "The task was interrupted. Choosing an answer resumes it with your direction.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(12.dp)) { Text(decision.answer, Modifier.fillMaxWidth().padding(12.dp), color = colors.onSecondaryContainer) }
                if (!waiting && !decision.cancelled) Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Continue with my choice") }
            }
            TextButton(onClick = onStop) { Text("Stop this task") }
        }
    }
}

@Composable
internal fun GitHubLiveProgressCard(summary: String, milestones: List<String>) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("github_live_activity"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow), border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = colors.primary)
                Text("Working in your repository", style = MaterialTheme.typography.titleSmall, color = colors.primary)
            }
            Text(summary.ifBlank { "Preparing your task. Live updates appear here while work continues." }, style = MaterialTheme.typography.bodyMedium)
            milestones.distinct().takeLast(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            Text("Updates every few seconds · Final answer replaces this activity", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GitHubChatPresenceEffect(chatId: Int?) {
    val owner = remember { java.util.UUID.randomUUID().toString() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle, chatId) {
        fun update() {
            if (chatId != null && chatId > 0 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) GitHubChatPresence.show(owner, chatId) else GitHubChatPresence.hide(owner)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            GitHubChatPresence.hide(owner)
        }
    }
}
