package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun formatMessageTimestamp(timestampMillis: Long?): String {
    if (timestampMillis == null || timestampMillis <= 0) return ""
    return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMillis))
}

internal fun shouldShowContinuePrompt(
    text: String,
    isLoading: Boolean,
    isLastMessage: Boolean = false
): Boolean {
    return ChatResponseActionParser.shouldShowContinuePrompt(text, isLoading, isLastMessage)
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
        Card(
            modifier = modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
            shape = RoundedCornerShape(32.dp), colors = cardColor
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
                ChatMarkdown(content = text)
                if (formattedTime.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
        if (hasDetails) {
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(top = 4.dp, end = 4.dp),
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
    }
}

@Composable
fun OpponentChatBubble(
    modifier: Modifier = Modifier,
    canEdit: Boolean = false,
    canRetry: Boolean = false,
    isLoading: Boolean = false,
    isError: Boolean = false,
    isFavorite: Boolean = false,
    debugMode: Boolean = false,
    text: String = "",
    timestamp: Long? = null,
    thoughts: String = "",
    timeline: List<AssistantTimelineItem> = emptyList(),
    attachments: List<String> = emptyList(),
    agentRun: AgentRun? = null,
    runNotices: List<ChatRunNotice> = emptyList(),
    toolEvents: List<ToolEvent> = emptyList(),
    contentIdentity: Any = text,
    revisionIndexLabel: String? = null,
    canShowPreviousRevision: Boolean = false,
    canShowNextRevision: Boolean = false,
    isUserTyping: Boolean = false,
    isLastMessage: Boolean = false,
    onCopyClick: () -> Unit = {},
    onSelectClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onFavoriteLongPress: () -> Unit = {},
    onShowPreviousRevision: () -> Unit = {},
    onShowNextRevision: () -> Unit = {},
    onContinueClick: (() -> Unit)? = null,
    onActionClick: ((String) -> Unit)? = null
) {
    // Bubble background 50% more opaque than 0.04f (0.06f)
    val normalColor = Color.Black.copy(alpha = 0.06f)
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
    val showContinueAction = remember(text, isLoading, isLastMessage) { shouldShowContinuePrompt(text, isLoading, isLastMessage) }
    val dynamicActions = remember(text, isLoading) { ChatResponseActionParser.extractDynamicActions(text, isLoading) }
    var continueDismissed by rememberSaveable(contentIdentity) { mutableStateOf(false) }
    var actionDismissed by rememberSaveable(contentIdentity) { mutableStateOf(false) }

    // Suggestion Button Hold-to-Highlight State
    val highlightProgress = remember { Animatable(0f) }
    var activeHighlightedSentence by remember { mutableStateOf<String?>(null) }
    var activeHighlightJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun startHighlight(buttonText: String, promptText: String) {
        val matchingSentence = SuggestionHighlightManager.findMatchingSentence(text, buttonText, promptText)
        if (matchingSentence != null) {
            activeHighlightedSentence = matchingSentence
            activeHighlightJob?.cancel()
            activeHighlightJob = coroutineScope.launch {
                val current = highlightProgress.value
                val remainingRatio = (1f - current).coerceAtLeast(0f)
                val duration = (SuggestionHighlightManager.ANIMATION_DURATION_MS * remainingRatio).toInt()
                highlightProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing)
                )
            }
        }
    }

    fun reverseHighlight() {
        activeHighlightJob?.cancel()
        activeHighlightJob = coroutineScope.launch {
            val current = highlightProgress.value
            val duration = (SuggestionHighlightManager.ANIMATION_DURATION_MS * current).toInt()
            highlightProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            )
            if (highlightProgress.value == 0f) {
                activeHighlightedSentence = null
            }
        }
    }

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
                val hasUnavailableOrder = remember(contentTimeline, text, thoughts, toolEvents) {
                    hasUnavailableAssistantOrder(contentTimeline, text, thoughts, toolEvents.isNotEmpty())
                }

                // Response content (rendered first so details panel appears below during streaming)
                if (contentTimeline.isNotEmpty() && !hasUnavailableOrder) {
                    AssistantAnswerContent(
                        timeline = contentTimeline,
                        isLoading = showAnswerStreamingIndicator,
                        contentIdentity = contentIdentity,
                        highlightSentence = activeHighlightedSentence,
                        highlightProgress = highlightProgress.value
                    )
                } else {
                    LegacyAssistantAnswerContent(
                        cardColor = cardColor,
                        text = text,
                        thoughts = thoughts,
                        isLoading = showAnswerStreamingIndicator,
                        contentIdentity = contentIdentity,
                        highlightSentence = activeHighlightedSentence,
                        highlightProgress = highlightProgress.value
                    )
                }

                // Details expandable content & toggle button placed below the response content
                if (hasDetails) {
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp, end = 12.dp),
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

                if (debugMode && !isLoading) {
                    ChatDebugDiagnosticsCard(
                        agentRun = agentRun,
                        telemetryNotice = telemetryNotice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // Dynamic Action Buttons strip (if assistant proposed choices or options)
                if (dynamicActions.isNotEmpty() && onActionClick != null && !actionDismissed && !isUserTyping && !isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dynamicActions.forEach { action ->
                            val icon = when (action.iconType) {
                                ActionIconType.SEARCH -> Icons.Default.Search
                                ActionIconType.SUMMARIZE -> Icons.Default.Description
                                ActionIconType.EXPLAIN -> Icons.Default.HelpOutline
                                ActionIconType.CONFIRM -> Icons.Default.Check
                                ActionIconType.CANCEL -> Icons.Default.Close
                                ActionIconType.OPTION -> Icons.Default.AutoAwesome
                                ActionIconType.DEFAULT -> Icons.AutoMirrored.Filled.ArrowForward
                            }

                            Box(
                                modifier = Modifier.pointerInput(action) {
                                    detectTapGestures(
                                        onPress = {
                                            startHighlight(action.label, action.actionPrompt)
                                            val released = tryAwaitRelease()
                                            reverseHighlight()
                                            if (released) {
                                                actionDismissed = true
                                                onActionClick(action.actionPrompt)
                                            }
                                        }
                                    )
                                }
                            ) {
                                AssistChip(
                                    onClick = { /* Handled by pointerInput onPress/release */ },
                                    label = {
                                        Text(
                                            text = action.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                )
                            }
                        }
                    }
                }

                // Minimal transparent continuation chip & bottom-right aligned timestamp
                val isContinueVisible = showContinueAction && onContinueClick != null && !continueDismissed && !isUserTyping
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

                        Box(
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        startHighlight("continue", "continue")
                                        val released = tryAwaitRelease()
                                        reverseHighlight()
                                        if (released) {
                                            continueDismissed = true
                                            onContinueClick?.invoke()
                                        }
                                    }
                                )
                            }
                        ) {
                            SuggestionChip(
                                onClick = { /* Handled by pointerInput onPress/release */ },
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
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (formattedTime.isNotBlank()) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier
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
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                role = Role.Button
                contentDescription = if (isEnabled) {
                    if (isVisible) expandedDesc else collapsedDesc
                } else {
                    unavailableDesc
                }
                stateDescription = if (isEnabled) {
                    if (isVisible) expandedDesc else collapsedDesc
                } else {
                    unavailableDesc
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.details),
            style = MaterialTheme.typography.labelSmall,
            color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
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
    val events = remember(toolEvents) { toolEvents.associateBy(ToolEvent::sequence) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        timeline.forEachIndexed { index, item ->
            when (item.type) {
                AssistantTimelineItemType.THINKING -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                ) {
                    ThinkingBlock(
                        modifier = Modifier.fillMaxWidth(),
                        thoughts = item.content,
                        contentIdentity = "$contentIdentity:thinking:$index",
                        isLoading = isLoading && index == timeline.lastIndex
                    )
                }
                AssistantTimelineItemType.TEXT -> {
                    val parsed = remember(item.content) { ThinkingParser.extractThinking(item.content) }
                    val thinking = parsed.thinking
                    if (!thinking.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                        ) {
                            ThinkingBlock(
                                modifier = Modifier.fillMaxWidth(),
                                thoughts = thinking,
                                contentIdentity = "$contentIdentity:parsed-thinking:$index",
                                isLoading = isLoading && parsed.isThinking && index == timeline.lastIndex
                            )
                        }
                    }
                }
                AssistantTimelineItemType.TOOL -> item.toolSequence?.let(events::get)?.let { event ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
                    ) {
                        ToolTraceBlock(
                            events = listOf(event),
                            modifier = Modifier.fillMaxWidth(),
                            contentIdentity = "$contentIdentity:tool:${event.sequence}"
                        )
                    }
                }
                AssistantTimelineItemType.NOTICE,
                AssistantTimelineItemType.LEGACY_ORDER -> Unit
            }
        }
    }
}

@Composable
private fun AssistantAnswerContent(
    timeline: List<AssistantTimelineItem>,
    isLoading: Boolean,
    contentIdentity: Any,
    highlightSentence: String? = null,
    highlightProgress: Float = 0f
) {
    val textItems = remember(timeline) {
        timeline.mapIndexedNotNull { index, item ->
            if (item.type == AssistantTimelineItemType.TEXT) {
                index to item
            } else {
                null
            }
        }
    }
    textItems.forEach { (index, item) ->
        val parsed = remember(item.content) { ThinkingParser.extractThinking(item.content) }
        val isLastTextItem = index == textItems.lastOrNull()?.first
        val display = parsed.response + if (isLoading && isLastTextItem) "●" else ""
        if (display.isNotBlank() || (isLoading && isLastTextItem)) {
            ChatMarkdown(
                content = display,
                contentIdentity = "$contentIdentity:text:$index",
                highlightSentence = highlightSentence,
                highlightProgress = highlightProgress,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        if (showOrderNotice) {
            Text(
                text = stringResource(R.string.legacy_assistant_order_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }
        if (thoughts.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
            ) {
                ThinkingBlock(
                    modifier = Modifier.fillMaxWidth(),
                    thoughts = thoughts,
                    contentIdentity = contentIdentity,
                    isLoading = isLoading
                )
            }
        }
        if (toolEvents.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
            ) {
                ToolTraceBlock(
                    events = toolEvents,
                    modifier = Modifier.fillMaxWidth(),
                    contentIdentity = contentIdentity
                )
            }
        }
    }
}

@Composable
private fun LegacyAssistantAnswerContent(
    cardColor: CardColors,
    text: String,
    thoughts: String,
    isLoading: Boolean,
    contentIdentity: Any,
    highlightSentence: String? = null,
    highlightProgress: Float = 0f
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
                    highlightSentence = highlightSentence,
                    highlightProgress = highlightProgress,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun GPTMobileIcon(loading: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Color(0xFF00E5FF),
                strokeWidth = 2.5.dp,
                trackColor = Color.Transparent
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF00BCD4)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_gpt_mobile_no_padding),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }
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
        Spacer(Modifier.width(12.dp))
    }
    if (selected) {
        FilledTonalButton(
            onClick = onPlatformClick,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            contentPadding = PaddingValues(0.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onPlatformClick,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(0.dp),
            content = content
        )
    }
}
