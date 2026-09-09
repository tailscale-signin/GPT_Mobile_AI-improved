package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.*
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.theme.fastEffectsSpec
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import java.io.File

@Composable
fun UserChatBubble(modifier: Modifier = Modifier, text: String, files: List<String> = emptyList(), onLongPress: () -> Unit) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContentColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
    )
    Column(horizontalAlignment = Alignment.End) {
        Card(
            modifier = modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
            shape = RoundedCornerShape(32.dp), colors = cardColor
        ) { ChatMarkdown(content = text, modifier = Modifier.padding(16.dp)) }
        MessageFileThumbnailRow(files = files, modifier = Modifier.padding(top = 8.dp))
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
    onFavoriteLongPress: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {}
) {
    val normalColor = MaterialTheme.colorScheme.background
    val bubbleColor = animateColorAsState(
        targetValue = if (isFavorite) Color.Cyan.copy(alpha = 0.2f) else normalColor,
        animationSpec = tween(durationMillis = 500),
        label = "favoriteBubbleColor"
    ).value
    val cardColor = CardColors(
        containerColor = bubbleColor,
        contentColor = MaterialTheme.colorScheme.onBackground,
        disabledContentColor = normalColor.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
    )
    val noticeMessages = remember(runNotices, timeline, isLoading) {
        visibleChatRunNotices(runNotices, timelineNoticeMessages(timeline), isLoading)
    }
    val contentTimeline = remember(timeline) { timeline.filter { it.type != AssistantTimelineItemType.NOTICE } }

    var areDetailsVisible by rememberSaveable(contentIdentity) {
        mutableStateOf(isLoading)
    }

    val hasDetails = remember(contentTimeline, thoughts, toolEvents) {
        hasAssistantProcessDetails(
            timeline = contentTimeline,
            fallbackThoughts = thoughts,
            hasToolEvents = toolEvents.isNotEmpty()
        )
    }

    val showAnswerStreamingIndicator = isLoading
    val showProcessStreamingIndicator = showAnswerStreamingIndicator && text.isBlank()

    Column(modifier = modifier) {
        RunNoticeChips(notices = noticeMessages, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
        AgentRunStatusBlock(run = agentRun, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
        Column(
            modifier = Modifier.background(
                color = bubbleColor,
                shape = RoundedCornerShape(32.dp)
            )
        ) {
            val hasUnavailableOrder = remember(contentTimeline, text, thoughts, toolEvents) {
                hasUnavailableAssistantOrder(contentTimeline, text, thoughts, toolEvents.isNotEmpty())
            }

            AnimatedContent(
                targetState = areDetailsVisible && hasDetails,
                transitionSpec = { fadeIn(fastEffectsSpec()) togetherWith fadeOut(fastEffectsSpec()) },
                label = "assistantProcessDetails"
            ) { isVisible ->
                if (isVisible) {
                    if (contentTimeline.isNotEmpty() && !hasUnavailableOrder) {
                        AssistantProcessContent(
                            timeline = contentTimeline,
                            toolEvents = toolEvents,
                            isLoading = showProcessStreamingIndicator,
                            contentIdentity = contentIdentity
                        )
                    } else {
                        LegacyAssistantProcessContent(
                            thoughts = thoughts,
                            toolEvents = toolEvents,
                            isLoading = showProcessStreamingIndicator,
                            contentIdentity = contentIdentity,
                            showOrderNotice = hasUnavailableOrder
                        )
                    }
                }
            }

            if (contentTimeline.isNotEmpty() && !hasUnavailableOrder) {
                AssistantAnswerContent(
                    timeline = contentTimeline,
                    isLoading = showAnswerStreamingIndicator,
                    contentIdentity = contentIdentity
                )
            } else {
                LegacyAssistantAnswerContent(
                    cardColor = cardColor,
                    text = text,
                    thoughts = thoughts,
                    isLoading = showAnswerStreamingIndicator,
                    contentIdentity = contentIdentity
                )
            }

            MessageFileThumbnailRow(
                files = attachments,
                usePrimaryColors = false,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DetailsButton(
                    isVisible = areDetailsVisible,
                    isEnabled = hasDetails,
                    onClick = { areDetailsVisible = !areDetailsVisible }
                )

                Spacer(modifier = Modifier.weight(1f))

                if (!isLoading) {
                    if (!isError) {
                        CopyTextIcon(onCopyClick)
                        Spacer(Modifier.width(8.dp))
                        SelectTextIcon(onSelectClick)
                        Spacer(Modifier.width(8.dp))
                        FavoriteIcon(isFavorite, onFavoriteClick, onFavoriteLongPress)
                        if (canEdit) {
                            Spacer(Modifier.width(8.dp))
                            EditTextIcon(onEditClick)
                        }
                    }
                    if (canRetry) {
                        Spacer(Modifier.width(8.dp))
                        RetryIcon(onRetryClick)
                    }
                }
            }

            if (!isLoading && canRetry) {
                Text(
                    text = stringResource(R.string.retry_tools_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            if (!isLoading) {
                revisionIndexLabel?.let { label ->
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(enabled = canShowPreviousRevision, onClick = onShowPreviousRevision) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                stringResource(R.string.previous_revision)
                            )
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(enabled = canShowNextRevision, onClick = onShowNextRevision) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                stringResource(R.string.next_revision)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OpponentResponseContainer(
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val normalColor = Color.Transparent
    val bubbleColor = animateColorAsState(
        targetValue = if (isFavorite) Color.Cyan.copy(alpha = 0.2f) else normalColor,
        animationSpec = tween(durationMillis = 500),
        label = "favoriteResponseContainerColor"
    ).value

    Column(
        modifier = modifier
            .background(
                color = bubbleColor,
                shape = RoundedCornerShape(32.dp)
            )
            .padding(if (isFavorite) PaddingValues(top = 8.dp, bottom = 4.dp) else PaddingValues(0.dp)),
        content = content
    )
}

@Composable
internal fun DetailsButton(
    isVisible: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isVisible) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "detailsArrowRotation"
    )

    val expandedDesc = stringResource(R.string.tool_trace_collapse)
    val collapsedDesc = stringResource(R.string.tool_trace_expand)
    val unavailableDesc = stringResource(R.string.details_unavailable)

    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(
                enabled = isEnabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                role = Role.Button
                stateDescription = if (!isEnabled) {
                    unavailableDesc
                } else if (isVisible) {
                    expandedDesc
                } else {
                    collapsedDesc
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation),
            tint = if (isEnabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.details),
            style = MaterialTheme.typography.labelSmall,
            color = if (isEnabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            }
        )
    }
}

internal fun hasAssistantProcessDetails(
    timeline: List<AssistantTimelineItem>,
    fallbackThoughts: String,
    hasToolEvents: Boolean
): Boolean {
    val hasTimelineDetails = timeline.any { item ->
        when (item.type) {
            AssistantTimelineItemType.THINKING -> !item.content.isNullOrBlank()
            AssistantTimelineItemType.TOOL -> true
            AssistantTimelineItemType.TEXT -> {
                val parsed = ThinkingParser.extractThinking(item.content.orEmpty())
                !parsed.thinking.isNullOrBlank()
            }
            AssistantTimelineItemType.NOTICE,
            AssistantTimelineItemType.LEGACY_ORDER -> false
        }
    }
    return hasTimelineDetails || fallbackThoughts.isNotBlank() || hasToolEvents
}

@Composable
private fun AssistantProcessContent(
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    isLoading: Boolean,
    contentIdentity: Any
) {
    val events = remember(toolEvents) { toolEvents.associateBy(ToolEvent::sequence) }
    timeline.forEachIndexed { index, item ->
        when (item.type) {
            AssistantTimelineItemType.THINKING -> ThinkingBlock(
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                thoughts = item.content.orEmpty(),
                contentIdentity = "$contentIdentity:thinking:$index",
                isLoading = isLoading && index == timeline.lastIndex
            )
            AssistantTimelineItemType.TEXT -> {
                val parsed = remember(item.content) { ThinkingParser.extractThinking(item.content.orEmpty()) }
                if (parsed.thinking.orEmpty().isNotBlank()) {
                    ThinkingBlock(
                        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                        thoughts = parsed.thinking.orEmpty(),
                        contentIdentity = "$contentIdentity:parsed-thinking:$index",
                        isLoading = isLoading && parsed.isThinking && index == timeline.lastIndex
                    )
                }
            }
            AssistantTimelineItemType.TOOL -> item.toolSequence?.let(events::get)?.let { event ->
                ToolTraceBlock(
                    events = listOf(event),
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp),
                    contentIdentity = "$contentIdentity:tool:${event.sequence}"
                )
            }
            AssistantTimelineItemType.NOTICE,
            AssistantTimelineItemType.LEGACY_ORDER -> Unit
        }
    }
}

@Composable
private fun AssistantAnswerContent(
    timeline: List<AssistantTimelineItem>,
    isLoading: Boolean,
    contentIdentity: Any
) {
    val textItems = remember(timeline) {
        timeline.mapIndexedNotNull { index, item ->
            if (item.type == AssistantTimelineItemType.TEXT) index to item else null
        }
    }
    textItems.forEach { (index, item) ->
        val parsed = remember(item.content) { ThinkingParser.extractThinking(item.content.orEmpty()) }
        val isLastTextItem = index == textItems.lastOrNull()?.first
        val display = parsed.response + if (isLoading && isLastTextItem) "●" else ""
        if (display.isNotBlank() || (isLoading && isLastTextItem)) {
            ChatMarkdown(
                content = display,
                contentIdentity = "$contentIdentity:text:$index",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun LegacyAssistantProcessContent(
    thoughts: String,
    toolEvents: List<ToolEvent>,
    isLoading: Boolean,
    contentIdentity: Any,
    showOrderNotice: Boolean
) {
    if (showOrderNotice) {
        Text(
            text = stringResource(R.string.legacy_assistant_order_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)
        )
    }
    if (thoughts.isNotBlank()) {
        ThinkingBlock(
            modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
            thoughts = thoughts,
            contentIdentity = contentIdentity,
            isLoading = isLoading
        )
    }
    if (toolEvents.isNotEmpty()) {
        ToolTraceBlock(
            events = toolEvents,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp),
            contentIdentity = contentIdentity
        )
    }
}

@Composable
private fun LegacyAssistantAnswerContent(
    cardColor: CardColors,
    text: String,
    thoughts: String,
    isLoading: Boolean,
    contentIdentity: Any
) {
    val parsed = remember(text) {
        if (thoughts.isBlank() && text.contains("<think", ignoreCase = true)) {
            ThinkingParser.extractThinking(text)
        } else {
            null
        }
    }
    val response = parsed?.response ?: text
    val display = response + if (isLoading) "●" else ""
    if (display.isNotBlank() || isLoading) {
        Card(shape = RoundedCornerShape(32.dp), colors = cardColor) {
            Column {
                ChatMarkdown(
                    content = display,
                    contentIdentity = contentIdentity,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun GPTMobileIcon(loading: Boolean) {
    Box(
        modifier = Modifier.padding(start = 8.dp).size(40.dp).clip(RoundedCornerShape(40.dp)).background(Color.Cyan),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = Color.White,
            trackColor = Color.Transparent
        )
        Icon(
            painter = painterResource(R.drawable.ic_gpt_mobile_no_padding),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color.White
        )
    }
}

@Composable
fun PlatformButton(isLoading: Boolean, name: String, selected: Boolean, onPlatformClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Spacer(Modifier.width(12.dp))
        if (isLoading) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        Text(
            name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp)); if (isLoading) Spacer(Modifier.width(4.dp))
    }
    TextButton(
        modifier = Modifier.widthIn(max = 160.dp), onClick = onPlatformClick,
        colors = if (selected) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors(),
        content = content
    )
}

@Composable private fun CopyTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(ImageVector.vectorResource(R.drawable.ic_copy), stringResource(R.string.copy_text))
}
@Composable private fun SelectTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(ImageVector.vectorResource(R.drawable.ic_select), stringResource(R.string.select_text))
}
@Composable
private fun FavoriteIcon(
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onFavoriteLongPress: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onFavoriteClick() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFavoriteLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            stringResource(if (isFavorite) R.string.unfavorite else R.string.favorite),
            tint = if (isFavorite) Color.Cyan else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable private fun RetryIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Rounded.Refresh, stringResource(R.string.retry))
}
@Composable private fun EditTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
}

@Preview
@Composable
fun UserChatBubblePreview() {
    val sampleText = "How can I print hello world in Python?"
    GPTMobileTheme {
        UserChatBubble(text = sampleText, files = emptyList(), onLongPress = {})
    }
}

@Composable
internal fun MessageFileThumbnailRow(files: List<String>, modifier: Modifier = Modifier, usePrimaryColors: Boolean = true) {
    val validFiles = remember(files) { files.filter(String::isNotBlank) }
    if (validFiles.isEmpty()) return
    Row(
        modifier = modifier.wrapContentHeight().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { validFiles.forEach { MessageFileThumbnail(it, usePrimaryColors) } }
}

@Composable
private fun MessageFileThumbnail(filePath: String, usePrimaryColors: Boolean) {
    val file = remember(filePath) { File(filePath) }
    val isImage = remember(file.extension) { isImageFile(file.extension) }
    val container = if (usePrimaryColors) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f) else MaterialTheme.colorScheme.surfaceVariant
    val content = if (usePrimaryColors) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(container)) {
            Icon(
                ImageVector.vectorResource(if (isImage) R.drawable.ic_image else R.drawable.ic_file), file.name,
                modifier = Modifier.fillMaxWidth().padding(8.dp), tint = content
            )
        }
        Text(
            file.name, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).width(56.dp)
        )
    }
}

private fun isImageFile(extension: String?): Boolean =
    extension != null && extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
