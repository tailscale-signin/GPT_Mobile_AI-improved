package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeDecisionReceipt
import dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import kotlin.math.abs

internal val LocalGitHubNativeDecisions = staticCompositionLocalOf { emptyList<GitHubNativeDecisionReceipt>() }

internal data class GitHubDecisionBubbleData(val label: String, val summary: String, val rejected: Boolean = false, val choice: Boolean = false)

private fun gitHubCommitCommandSha(text: String): String? =
    text.takeIf { it.startsWith("GitHub workspace: ") }?.let(GitHubTaskContinuity::publishedCommit)

internal fun gitHubCommitDecisionBubble(text: String, receipts: List<GitHubNativeDecisionReceipt>): GitHubDecisionBubbleData? {
    val sha = gitHubCommitCommandSha(text) ?: return null
    return receipts.firstOrNull { it.sha.equals(sha, true) }?.bubble()
        ?: GitHubDecisionBubbleData("Saved commit continuation", sha.take(10))
}

internal fun GitHubNativeDecisionReceipt.bubble(): GitHubDecisionBubbleData {
    val feature = summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(110).ifBlank { "Proposed changes" }
    val title = when {
        sha == null -> "Rejected commit"
        automatic -> "Auto-accepted commit"
        else -> "Accepted commit"
    }
    return GitHubDecisionBubbleData(title, "$feature · $branch", rejected = sha == null)
}

internal fun GitHubChatDecision.bubble(): GitHubDecisionBubbleData? {
    val selected = answer ?: return null
    return if (cancelled) {
        GitHubDecisionBubbleData("Stopped task", question.take(110), rejected = true, choice = true)
    } else {
        GitHubDecisionBubbleData(if (automatic) "Suggested choice accepted" else "Choice saved", selected.take(150), choice = true)
    }
}

@Composable
internal fun rememberGitHubTurnDecisions(choices: List<GitHubChatDecision>, commits: List<GitHubNativeDecisionReceipt>, messages: List<MessageV2>, queue: List<PendingPrompt>): Map<Int, List<GitHubDecisionBubbleData>> =
    remember(choices, commits, messages, queue) {
        val visibleCommits = if (commits.any { it.sha != null }) {
            (messages.asSequence().map { it.content } + queue.asSequence().map { it.text }).mapNotNull(::gitHubCommitCommandSha).toSet()
        } else {
            emptySet()
        }
        val completedChoices = choices.filter { it.delivered }.mapNotNull { decision -> decision.bubble()?.let { decision.sourceMessageId to it } }
        val otherReceipts = commits.filter { it.sha?.lowercase(java.util.Locale.ROOT) !in visibleCommits }.map { it.sourceMessageId to it.bubble() }
        (completedChoices + otherReceipts).groupBy({ it.first }, { it.second })
    }

@Composable
internal fun GitHubTurnDecisions(values: List<GitHubDecisionBubbleData>) {
    if (values.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        values.forEach { GitHubDecisionBubble(it) }
    }
}

@Composable
internal fun GitHubDecisionBubble(value: GitHubDecisionBubbleData, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = if (value.rejected) colors.onSurfaceVariant else colors.primary
    Surface(
        modifier = modifier.testTag("github_decision_receipt"),
        shape = RoundedCornerShape(18.dp),
        color = if (value.rejected) colors.surfaceContainerHigh else colors.primaryContainer.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    value.rejected -> Icons.Rounded.Close
                    value.choice -> Icons.Rounded.Tune
                    else -> Icons.Rounded.CheckCircle
                },
                null,
                Modifier.size(17.dp),
                tint = accent
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value.label, style = MaterialTheme.typography.labelLarge, color = accent)
                Text(value.summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Paint-only motion, shared with chat progress so reduced motion stays respected. */
@Composable
internal fun Modifier.gitHubDecisionOutline(shape: Shape, active: Boolean = true): Modifier {
    if (!active) return this
    val phase = rememberGitHubProgressMotion(active = true)
    val primary = MaterialTheme.colorScheme.primary
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(1.5.dp.toPx())
        onDrawWithContent {
            drawContent()
            val strength = 1f - abs(phase.value * 2f - 1f)
            drawOutline(outline, primary.copy(alpha = 0.26f + strength * 0.56f), style = stroke)
        }
    }
}

@Composable
internal fun GitHubPendingPrompt(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).gitHubDecisionOutline(MaterialTheme.shapes.large), contentAlignment = Alignment.Center) { content() }
}
