package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Keep the active request in the composer area while the conversation shows progress. */
@Composable
internal fun ActiveGitHubPrompt(message: MessageV2, decision: GitHubDecisionBubbleData?, onViewPrompt: (String) -> Unit) {
    if (decision != null) {
        GitHubDecisionBubble(decision, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("github_active_prompt"))
        return
    }
    val prompt = remember(message.content) {
        ConversationSubject.withoutMetadata(message.content).substringBefore("\n[github-workflow-preset:").trim()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("github_active_prompt"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier
                .then(if (prompt.isNotBlank()) Modifier.clickable(onClickLabel = "Read full request") { onViewPrompt(prompt) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("Current request", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                prompt.ifBlank { message.attachments.joinToString { it.resolvedDisplayName } },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
