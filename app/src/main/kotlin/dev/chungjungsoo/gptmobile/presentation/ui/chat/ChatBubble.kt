package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.hasUnavailableAssistantOrder
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import java.io.File

@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    files: List<String> = emptyList(),
    onLongPress: () -> Unit
) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContentColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
    )

    Column(horizontalAlignment = Alignment.End) {
        Card(
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress.invoke() })
                },
            shape = RoundedCornerShape(32.dp),
            colors = cardColor
        ) {
            ChatMarkdown(
                content = text,
                modifier = Modifier.padding(16.dp)
            )
        }
        MessageFileThumbnailRow(
            files = files,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canRetry: Boolean,
    isLoading: Boolean,
    isError: Boolean = false,
    text: String,
    thoughts: String = "",
    timeline: List<AssistantTimelineItem> = emptyList(),
    attachments: List<String> = emptyList(),
    agentRun: AgentRun? = null,
    runNotices: List<ChatRunNotice> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    contentIdentity: Any = text,
    canEdit: Boolean = false,
    isFavorite: Boolean = false,
    revisionIndexLabel: String? = null,
    canShowPreviousRevision: Boolean = false,
    canShowNextRevision: Boolean = false,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {}
) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        disabledContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
    )

    val noticeMessages = remember(runNotices, timeline, isLoading) {
        visibleChatRunNotices(
            stored = runNotices,
            timelineNotices = timelineNoticeMessages(timeline),
            isRunActive = isLoading
        )
    }
    val contentTimeline = remember(timeline) {
        timeline.filter { it.type != AssistantTimelineItemType.NOTICE }
    }

    Column(modifier = modifier) {
        RunNoticeChips(
            notices = noticeMessages,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
        )
        AgentRunStatusBlock(
            run = agentRun,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
        )

        Column {
            val hasUnavailableOrder = remember(contentTimeline, text, thoughts, toolEvents) {
                hasUnavailableAssistantOrder(
                    timeline = contentTimeline,
                    content = text,
                    thoughts = thoughts,
                    hasToolEvents = toolEvents.isNotEmpty()
                )
            }
            if (contentTimeline.isNotEmpty() && !hasUnavailableOrder) {
                AssistantTimelineContent(
                    timeline = contentTimeline,
                    toolEvents = toolEvents,
                    isLoading = isLoading,
                    contentIdentity = contentIdentity
                )
                MessageFileThumbnailRow(
                    files = attachments,
                    usePrimaryColors = false,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                LegacyAssistantContent(
                    cardColor = cardColor,
                    text = text,
                    thoughts = thoughts,
                    toolEvents = toolEvents,
                    attachments = attachments,
                    isLoading = isLoading,
                    contentIdentity = contentIdentity,
                    showOrderNotice = hasUnavailableOrder
                )
            }

            if (!isLoading) {
                Row(
                    modifier = Modifier.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isError) {
                        CopyTextIcon(onCopyClick)
                        Spacer(modifier = Modifier.width(8.dp))
                        SelectTextIcon(onSelectClick)
                        Spacer(modifier = Modifier.width(8.dp))
                        FavoriteIcon(isFavorite = isFavorite, onFavoriteClick = onFavoriteClick)
                        if (canEdit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            EditTextIcon(onEditClick)
                        }
                    }
                    if (canRetry) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RetryIcon(onRetryClick)
                    }
                }
                if (canRetry) {
                    Text(
                        text = stringResource(R.string.retry_tools_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }

                revisionIndexLabel?.let { label ->
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            enabled = canShowPreviousRevision,
                            onClick = onShowPreviousRevision
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.previous_revision)
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            enabled = canShowNextRevision,
                            onClick = onShowNextRevision
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.next_revision)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantTimelineContent(
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    isLoading: Boolean,
    contentIdentity: Any
) {
    val toolEventsBySequence = remember(toolEvents) {
        toolEvents.associateBy(ToolEvent::sequence)
    }
    timeline.forEachIndexed { index, item ->
        when (item.type) {
            AssistantTimelineItemType.THINKING -> ThinkingBlock(
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                thoughts = item.content.orEmpty(),
                contentIdentity = "$contentIdentity:thinking:$index",
                isLoading = isLoading && index == timeline.lastIndex
            )

            AssistantTimelineItemType.TEXT -> {
                val itemContent = item.content.orEmpty()
                val parsed = remember(itemContent) { ThinkingParser.extractThinking(itemContent) }
                val parsedThinking = parsed.thinking.orEmpty()
                if (parsedThinking.isNotBlank()) {
                    ThinkingBlock(
                        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                        thoughts = parsedThinking,
                        contentIdentity = "$contentIdentity:parsed-thinking:$index",
                        isLoading = isLoading && parsed.isThinking && index == timeline.lastIndex
                    )
                }
                val actualText = parsed.response
                val displayText = if (isLoading && index == timeline.lastIndex) actualText + "●" else actualText
                if (displayText.isNotBlank()) {
                    ChatMarkdown(
                        content = displayText,
                        contentIdentity = "$contentIdentity:text:$index",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            AssistantTimelineItemType.TOOL ->
                item.toolSequence
                    ?.let(toolEventsBySequence::get)
                    ?.let { event ->
                        ToolTraceBlock(
                            events = listOf(event),
                            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                            contentIdentity = "$contentIdentity:tool:${event.sequence}"
                        )
                    }

            AssistantTimelineItemType.NOTICE -> Unit

            AssistantTimelineItemType.LEGACY_ORDER -> Unit
        }
    }
}

@Composable
private fun LegacyAssistantContent(
    cardColor: CardColors,
    text: String,
    thoughts: String,
    toolEvents: List<ToolEvent>,
    attachments: List<String>,
    isLoading: Boolean,
    contentIdentity: Any,
    showOrderNotice: Boolean
) {
    val parsed = remember(text) {
        if (thoughts.isBlank() && text.contains("<think", ignoreCase = true)) {
            ThinkingParser.extractThinking(text)
        } else {
            null
        }
    }
    val effectiveThoughts = parsed?.thinking ?: thoughts
    val effectiveResponseText = parsed?.response ?: text
    val isThinking = (isLoading && effectiveThoughts.isNotBlank() && effectiveResponseText.isBlank()) || (parsed?.isThinking == true)

    if (showOrderNotice) {
        Text(
            text = stringResource(R.string.legacy_assistant_order_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)
        )
    }
    if (effectiveThoughts.isNotBlank()) {
        ThinkingBlock(
            modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
            thoughts = effectiveThoughts,
            contentIdentity = contentIdentity,
            isLoading = isThinking
        )
    }
    ToolTraceBlock(
        events = toolEvents,
        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        contentIdentity = contentIdentity
    )
    Card(
        shape = RoundedCornerShape(0.dp),
        colors = cardColor
    ) {
        Column {
            ChatMarkdown(
                content = if (isLoading) effectiveResponseText + "●" else effectiveResponseText,
                contentIdentity = contentIdentity,
                modifier = Modifier.padding(16.dp)
            )
            MessageFileThumbnailRow(
                files = attachments,
                usePrimaryColors = false,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun GPTMobileIcon(loading: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(color = Color(0xFF00A67D)),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp)
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_gpt_mobile_no_padding),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun PlatformButton(
    isLoading: Boolean,
    name: String,
    selected: Boolean,
    onPlatformClick: () -> Unit
) {
    val buttonContent: @Composable RowScope.() -> Unit = {
        Spacer(modifier = Modifier.width(12.dp))

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (isLoading) Spacer(modifier = Modifier.width(4.dp))
    }

    TextButton(
        modifier = Modifier.widthIn(max = 160.dp),
        onClick = onPlatformClick,
        colors = if (selected) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors(),
        content = buttonContent
    )
}

@Composable
private fun CopyTextIcon(onCopyClick: () -> Unit) {
    IconButton(onClick = onCopyClick) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
            contentDescription = stringResource(R.string.copy_text)
        )
    }
}

@Composable
private fun SelectTextIcon(onSelectClick: () -> Unit) {
    IconButton(onClick = onSelectClick) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_select),
            contentDescription = stringResource(R.string.select_text)
        )
    }
}

@Composable
private fun FavoriteIcon(
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit
) {
    IconButton(onClick = onFavoriteClick) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(if (isFavorite) R.string.unfavorite else R.string.favorite),
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RetryIcon(onRetryClick: () -> Unit) {
    IconButton(onClick = onRetryClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.retry)
        )
    }
}

@Composable
private fun EditTextIcon(onEditClick: () -> Unit) {
    IconButton(onClick = onEditClick) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = stringResource(R.string.edit)
        )
    }
}

@Preview
@Composable
fun UserChatBubblePreview() {
    val sampleText = """
        How can I print hello world
        in Python?
    """.trimIndent()
    GPTMobileTheme {
        UserChatBubble(text = sampleText, files = emptyList(), onLongPress = {})
    }
}

@Preview
@Composable
fun OpponentChatBubblePreview() {
    val sampleText = """
        # Demo
    
        Emphasis, aka italics, with *asterisks* or _underscores_. Strong emphasis, aka bold, with **asterisks** or __underscores__. Combined emphasis with **asterisks and _underscores_**. [Links with two blocks, text in square-brackets, destination is in parentheses.](https://www.example.com). Inline `code` has `back-ticks around` it.
    
        1. First ordered list item
        2. Another item
            * Unordered sub-list.
        3. And another item.
            You can have properly indented paragraphs within list items. Notice the blank line above, and the leading spaces (at least one, but we'll use three here to also align the raw Markdown).
    
        * Unordered list can use asterisks
        - Or minuses
        + Or pluses
    """.trimIndent()
    GPTMobileTheme {
        OpponentChatBubble(
            text = sampleText,
            canRetry = true,
            isLoading = false,
            revisionIndexLabel = "Revision 1/1",
            onCopyClick = {},
            onRetryClick = {}
        )
    }
}

@Composable
internal fun MessageFileThumbnailRow(
    files: List<String>,
    modifier: Modifier = Modifier,
    usePrimaryColors: Boolean = true
) {
    // Filter out empty strings and check if we have valid files
    val validFiles = remember(files) {
        files.filter { it.isNotEmpty() && it.isNotBlank() }
    }

    if (validFiles.isEmpty()) {
        return
    }

    Row(
        modifier = modifier
            .wrapContentHeight()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        validFiles.forEach { filePath ->
            MessageFileThumbnail(
                filePath = filePath,
                usePrimaryColors = usePrimaryColors
            )
        }
    }
}

@Composable
private fun MessageFileThumbnail(
    filePath: String,
    usePrimaryColors: Boolean
) {
    val file = remember(filePath) { File(filePath) }
    val extension = file.extension
    val isImage = remember(extension) { isImageFile(extension) }
    val containerColor = if (usePrimaryColors) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (usePrimaryColors) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
        ) {
            if (isImage) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    tint = contentColor
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    tint = contentColor
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(56.dp)
        )
    }
}

private fun isImageFile(extension: String?): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    return extension != null && extension.lowercase() in imageExtensions
}
