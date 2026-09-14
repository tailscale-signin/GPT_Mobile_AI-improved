package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.*
import dev.chungjungsoo.gptmobile.data.localruntime.DiagnosticsTelemetryProvider
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.theme.fastEffectsSpec
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatMessageTimestamp(timestampMillis: Long?): String {
    if (timestampMillis == null || timestampMillis <= 0) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))
}

internal fun shouldShowContinuePrompt(text: String, isLoading: Boolean): Boolean {
    if (isLoading || text.isBlank()) return false
    val trimmed = text.trim()
    val lower = trimmed.lowercase(Locale.ROOT)
    return trimmed.endsWith("...") ||
        trimmed.endsWith("…") ||
        trimmed.endsWith("continue?") ||
        trimmed.endsWith("Continue?") ||
        lower.endsWith("continue") ||
        lower.contains("reply with continue") ||
        lower.contains("reply 'continue'") ||
        lower.contains("reply \"continue\"") ||
        lower.contains("say continue") ||
        lower.contains("type continue") ||
        lower.contains("would you like me to continue") ||
        lower.contains("would you like to continue") ||
        lower.contains("shall i continue") ||
        lower.contains("should i continue") ||
        lower.contains("do you want me to continue") ||
        lower.contains("do you want me to keep going") ||
        lower.contains("let me know if you want me to continue") ||
        lower.contains("let me know if you'd like me to continue") ||
        lower.contains("let me know if you want me to keep going") ||
        lower.contains("let me know if i should continue") ||
        lower.contains("proceed?") ||
        lower.endsWith("proceed") ||
        (trimmed.count { it == '`' } % 2 != 0) // unclosed code block / truncated
}

@Composable
fun UserChatBubble(
    modifier: Modifier = Modifier,
    text: String,
    timestamp: Long? = null,
    files: List<String> = emptyList(),
    hasDetails: Boolean = false,
    areDetailsVisible: Boolean = false,
    onToggleDetails: () -> Unit = {},
    onLongPress: () -> Unit
) {
    val cardColor = CardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContentColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
        disabledContainerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
    )
    val formattedTime = remember(timestamp) { formatMessageTimestamp(timestamp) }

    Column(horizontalAlignment = Alignment.End) {
        if (hasDetails) {
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(bottom = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DetailsButton(
                    isVisible = areDetailsVisible,
                    isEnabled = true,
                    onClick = onToggleDetails
                )
            }
        }
        Card(
            modifier = modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
            shape = RoundedCornerShape(32.dp), colors = cardColor
        ) { ChatMarkdown(content = text, modifier = Modifier.padding(16.dp)) }
        MessageFileThumbnailRow(files = files, modifier = Modifier.padding(top = 8.dp))
        if (formattedTime.isNotBlank()) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .alpha(0.5f)
                    .padding(top = 2.dp, end = 8.dp)
            )
        }
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canRetry: Boolean,
    isLoading: Boolean,
    isError: Boolean = false,
    text: String,
    timestamp: Long? = null,
    thoughts: String = "",
    timeline: List<AssistantTimelineItem> = emptyList(),
    attachments: List<String> = emptyList(),
    agentRun: AgentRun? = null,
    runNotices: List<ChatRunNotice> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    contentIdentity: Any = text,
    canEdit: Boolean = false,
    isFavorite: Boolean = false,
    debugMode: Boolean = false,
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
    onShowNextRevision: () -> Unit = {},
    onContinueClick: (() -> Unit)? = null
) {
    // Background bubble 3x more transparent (0.08f vs previous 0.25f)
    val normalColor = Color.Black.copy(alpha = 0.08f)
    val bubbleColor = animateColorAsState(
        targetValue = if (isFavorite) Color.Cyan.copy(alpha = 0.12f) else normalColor,
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
    val (telemetryNotice, nonTelemetryNotices) = remember(noticeMessages) {
        extractTelemetryNotice(noticeMessages)
    }
    val diagnosticsHudText = remember(agentRun, telemetryNotice, debugMode) {
        buildDiagnosticsHudText(agentRun, telemetryNotice, debugMode)
    }
    val formattedTime = remember(timestamp) { formatMessageTimestamp(timestamp) }
    val showContinueAction = remember(text, isLoading) { shouldShowContinuePrompt(text, isLoading) }
    var continueDismissed by rememberSaveable(contentIdentity) { mutableStateOf(false) }

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

    // Determine if the bubble has any visible content.
    // When details is collapsed, no AI generated response yet, and no notices/run info, hide bubble with 1-second fade.
    val hasVisibleText = text.isNotBlank() || (showAnswerStreamingIndicator && (!hasDetails || areDetailsVisible))
    val hasVisibleProcess = hasDetails && areDetailsVisible
    val hasVisibleExtras = nonTelemetryNotices.isNotEmpty() || agentRun != null || attachments.isNotEmpty() || (!isLoading && (canRetry || canEdit || isError))
    val shouldShowBubble = hasVisibleText || hasVisibleProcess || hasVisibleExtras

    Column(modifier = modifier) {
        RunNoticeChips(notices = nonTelemetryNotices, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        AgentRunStatusBlock(run = agentRun, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))

        AnimatedVisibility(
            visible = shouldShowBubble,
            enter = fadeIn(animationSpec = tween(1000)),
            exit = fadeOut(animationSpec = tween(1000))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = bubbleColor,
                        shape = RoundedCornerShape(32.dp)
                    )
            ) {
                // Details expand button moved to top-right corner of chat bubble header
                if (hasDetails) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailsButton(
                            isVisible = areDetailsVisible,
                            isEnabled = true,
                            onClick = { areDetailsVisible = !areDetailsVisible }
                        )
                    }
                }

                val hasUnavailableOrder = remember(contentTimeline, text, thoughts, toolEvents) {
                    hasUnavailableAssistantOrder(contentTimeline, text, thoughts, toolEvents.isNotEmpty())
                }

                AnimatedContent(
                    targetState = areDetailsVisible && hasDetails,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000))
                    },
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
                        diagnosticsHudText?.let { hudText ->
                            Spacer(Modifier.width(8.dp))
                            TelemetryBadge(hudText)
                        }
                    }
                }

                // In-depth Debug Mode Hardware & NPU Telemetry Inspector
                if (debugMode && !isLoading) {
                    ChatDebugDiagnosticsCard(
                        agentRun = agentRun,
                        telemetryNotice = telemetryNotice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // Minimal transparent continuation chip & bottom-right aligned timestamp
                val isContinueVisible = showContinueAction && onContinueClick != null && !continueDismissed
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        visible = isContinueVisible,
                        enter = fadeIn(tween(300)),
                        exit = fadeOut(tween(500))
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "continuePulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.45f,
                            targetValue = 0.95f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseAlpha"
                        )

                        SuggestionChip(
                            onClick = {
                                continueDismissed = true
                                onContinueClick?.invoke()
                            },
                            label = { Text("Continue") },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Continue",
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = pulseAlpha * 0.6f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (formattedTime.isNotBlank()) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .alpha(0.5f)
                                .padding(start = 8.dp)
                        )
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
                onClickLabel = if (isVisible) expandedDesc else collapsedDesc,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (isEnabled) {
                    if (isVisible) expandedDesc else collapsedDesc
                } else {
                    unavailableDesc
                }
                stateDescription = if (isVisible) expandedDesc else collapsedDesc
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.tool_trace_details),
            style = MaterialTheme.typography.labelSmall,
            color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun AssistantProcessContent(
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    isLoading: Boolean,
    contentIdentity: Any
) {
    var previousCompletedThoughts by remember(contentIdentity) { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        timeline.forEach { item ->
            when (item.type) {
                AssistantTimelineItemType.THINKING -> {
                    ThinkingBlock(
                        thoughts = item.content,
                        isLoading = isLoading,
                        previousCompletedThoughts = previousCompletedThoughts,
                        onCompletedThinkingRecorded = { previousCompletedThoughts = it }
                    )
                }

                AssistantTimelineItemType.TOOL_CALL -> {
                    val matchingEvent = toolEvents.firstOrNull { it.callId == item.callId }
                    matchingEvent?.let { ToolTraceBlock(it) }
                }

                AssistantTimelineItemType.NOTICE,
                AssistantTimelineItemType.TEXT -> Unit
            }
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
    var previousCompletedThoughts by remember(contentIdentity) { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (showOrderNotice) {
            Text(
                text = stringResource(R.string.details_order_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (thoughts.isNotBlank() || isLoading) {
            ThinkingBlock(
                thoughts = thoughts,
                isLoading = isLoading,
                previousCompletedThoughts = previousCompletedThoughts,
                onCompletedThinkingRecorded = { previousCompletedThoughts = it }
            )
        }
        toolEvents.forEach { toolEvent ->
            ToolTraceBlock(toolEvent)
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
        timeline.filter { it.type == AssistantTimelineItemType.TEXT }
    }
    val combinedText = remember(textItems) {
        textItems.joinToString(separator = "\n\n") { it.content }
    }

    ChatMarkdown(
        content = combinedText,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun LegacyAssistantAnswerContent(
    cardColor: CardColors,
    text: String,
    thoughts: String,
    isLoading: Boolean,
    contentIdentity: Any
) {
    val cleanText = remember(text) {
        ThinkingParser.stripThinking(text).trimStart()
    }

    ChatMarkdown(
        content = cleanText,
        modifier = Modifier.padding(16.dp)
    )
}

private fun hasAssistantProcessDetails(
    timeline: List<AssistantTimelineItem>,
    fallbackThoughts: String,
    hasToolEvents: Boolean
): Boolean {
    val hasTimelineProcess = timeline.any { item ->
        item.type == AssistantTimelineItemType.THINKING || item.type == AssistantTimelineItemType.TOOL_CALL
    }
    return hasTimelineProcess || fallbackThoughts.isNotBlank() || hasToolEvents
}

private fun hasUnavailableAssistantOrder(
    timeline: List<AssistantTimelineItem>,
    fallbackText: String,
    fallbackThoughts: String,
    hasToolEvents: Boolean
): Boolean {
    if (timeline.isNotEmpty()) return false
    val hasProcess = fallbackThoughts.isNotBlank() || hasToolEvents
    return hasProcess && fallbackText.isNotBlank()
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

@Composable
internal fun TelemetryBadge(notice: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = notice },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Rich diagnostics inspection card rendered when Debug Mode is turned on.
 * Displays real-time hardware status, QNN HTP NPU native readiness, and token generation speed.
 */
@Composable
internal fun ChatDebugDiagnosticsCard(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    val snapshot = remember(agentRun, telemetryNotice) {
        DiagnosticsTelemetryProvider.getSnapshot(
            context = context,
            backendName = agentRun?.modelSnapshot ?: "On-Device",
            accelerator = "NPU / Hexagon HTP"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Debug Diagnostics",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Debug Diagnostics HUD",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                TextButton(
                    onClick = {
                        val fullReport = DiagnosticsTelemetryProvider.formatDiagnosticsText(snapshot, telemetryNotice)
                        clipboardManager.setText(AnnotatedString(fullReport))
                        isCopied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isCopied) "Copied!" else "Copy Diagnostics",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Processor & Native Accelerator Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Processor: ${snapshot.socModel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = if (snapshot.qnnReady) "Hexagon NPU: Ready" else "Hexagon NPU: Inactive",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snapshot.qnnReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // Memory & Thermals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RAM: ${snapshot.availableRamMb} MB avail / ${snapshot.totalRamGb} GB",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "Thermal: ${snapshot.thermalStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            if (!telemetryNotice.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = telemetryNotice,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp
                )
            }
        }
    }
}

internal fun isTelemetryNotice(message: String): Boolean =
    message.startsWith("Local: ") && message.contains("tok/s")

internal fun extractTelemetryNotice(notices: List<String>): Pair<String?, List<String>> {
    val telemetry = notices.firstOrNull(::isTelemetryNotice)
    val remaining = notices.filterNot(::isTelemetryNotice)
    return telemetry to remaining
}

internal fun buildDiagnosticsHudText(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    debugMode: Boolean
): String? {
    if (!debugMode) return telemetryNotice

    val parts = mutableListOf<String>()
    agentRun?.modelSnapshot?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    agentRunDurationSeconds(agentRun ?: return telemetryNotice)?.let { duration ->
        parts.add("${duration}s")
    }
    telemetryNotice?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

    return if (parts.isNotEmpty()) parts.joinToString(" • ") else telemetryNotice
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
