package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.displayResult
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.uniformResponseSource
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchResponseBlock
import dev.chungjungsoo.gptmobile.data.research.feature.researchResponseBlocks
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedAssistantDisplay(val thinking: String = "", val citationText: String = "", val response: String = "")

internal fun prepareAssistantDisplay(raw: String, combined: Boolean): PreparedAssistantDisplay {
    val parsed = ThinkingParser.extractThinking(raw)
    val citationText = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(parsed.response)
    return PreparedAssistantDisplay(parsed.thinking.orEmpty(), citationText, if (combined) citationText else answerWithoutSourceLists(citationText))
}

private data class PreparedResearchBlocks(
    val response: String,
    val splitParagraphs: Boolean,
    val blocks: List<ResearchResponseBlock>
)

/** One disclosure controls the entire process; response text remains readable while collapsed. */
@Composable
internal fun AssistantChronologicalContent(
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    fallbackText: String,
    fallbackThoughts: String,
    contentIdentity: Any,
    isLoading: Boolean,
    animateResponse: Boolean = true,
    proseColor: androidx.compose.ui.graphics.Color? = null,
    responseSurface: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    debugMode: Boolean,
    showExecutionTrace: Boolean = true,
    responseIcon: (@Composable () -> Unit)? = null,
    beforeAnswer: @Composable () -> Unit = {},
    debugMemorySources: Map<String, DebugMemorySource> = emptyMap(),
    debugSettings: AppFeatureSettings = AppFeatureSettings(),
    showReasoning: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isError: Boolean = false,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner? = null,
    articleCards: @Composable (String) -> Unit = {},
    combinedWording: CombinedWordingPresentation? = null,
    splitForPassageIndicators: Boolean = false,
    passageIndicators: @Composable (String) -> Unit = {},
    combinedManifest: dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference? = null,
    originalSource: CombinedOriginalSourceReader? = null,
    loadCombinedBlocks: suspend (dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, Int, Int) -> List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> = { _, _, _ -> emptyList() }
) {
    val effectiveExecutionTrace = debugMode && showExecutionTrace
    val events = remember(toolEvents) { toolEvents.associateBy { it.sequence } }
    val recalledSources = remember(timeline, toolEvents, debugMemorySources, debugMode, debugSettings.debugShowMemoryRecall) {
        if (debugMode && debugSettings.debugShowMemoryRecall) {
            val automaticIds = timeline.flatMap { it.recalledFacts }.map { it.id }
            val toolIds = toolEvents
                .filter { !it.isError && (isMemoryRecallTool(it.toolName) || isMemoryRecallTool(it.modelToolName)) }
                .flatMap { rememberedMemoryIdsFromToolResult(it.displayResult()) }
            (automaticIds + toolIds).distinct().mapNotNull { debugMemorySources[it] }
        } else {
            emptyList()
        }
    }
    val legacy = timeline.isEmpty() || timeline.any { it.type == AssistantTimelineItemType.LEGACY_ORDER }
    val sourceItems = if (!legacy) {
        // Preserve chronological answer fragments in both display modes. Replacing
        // them with the aggregate fallback would reuse the first fragment's state
        // for different content and discard every later prepared fragment.
        if (timeline.none { it.type == AssistantTimelineItemType.TEXT } && fallbackText.isNotBlank()) {
            // A process-only timeline still needs its saved answer. Keep the
            // diagnostic entries and append the fallback as its sole text part.
            timeline + AssistantTimelineItem(AssistantTimelineItemType.TEXT, fallbackText)
        } else {
            timeline
        }
    } else if (!effectiveExecutionTrace) {
        if (fallbackText.isBlank()) emptyList() else listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, fallbackText))
    } else {
        buildList {
            if (fallbackThoughts.isNotBlank()) add(AssistantTimelineItem(AssistantTimelineItemType.THINKING, fallbackThoughts))
            addAll(toolEvents.sortedBy { it.sequence }.map { AssistantTimelineItem(AssistantTimelineItemType.TOOL, toolSequence = it.sequence) })
            if (fallbackText.isNotBlank()) add(AssistantTimelineItem(AssistantTimelineItemType.TEXT, fallbackText))
            addAll(timeline.filter { it.type == AssistantTimelineItemType.NOTICE })
        }
    }
    val items = if (effectiveExecutionTrace) sourceItems else sourceItems.filter { it.type == AssistantTimelineItemType.TEXT }
    val totalAnswerLength = maxOf(fallbackText.length, items.sumOf { if (it.type == AssistantTimelineItemType.TEXT) it.content.length else 0 })
    val answerIdentity = AssistantRendererIdentity(
        contentIdentity,
        outputTrace?.runId,
        outputTrace?.fence,
        combinedManifest?.revisionId ?: timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality?.revisionId,
        when {
            originalSource != null -> "source:${originalSource.source.platformUid}:${originalSource.source.originalDocument?.payloadId}"
            combinedManifest != null -> "document:${combinedManifest.id}"
            else -> "inline"
        }
    )
    val documentSource = originalSource?.source?.responseSource ?: timeline.uniformResponseSource()
    val hasGatewaySource = ResponseSourceAppearance.isGateway(documentSource) || sourceItems.any { ResponseSourceAppearance.isGateway(it.responseSource) }
    CompositionLocalProvider(LocalAnswerSurface provides responseSurface) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (responseIcon != null || effectiveExecutionTrace || isLoading && !LocalChatProgressInHeader.current) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    responseIcon?.invoke()
                    Column(Modifier.weight(1f)) {
                        if (isLoading && !LocalChatProgressInHeader.current) {
                            ChatGenerationProgress(toolEvents, items)
                        } else {
                            androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth())
                        }
                    }
                    if (effectiveExecutionTrace && (timeline.any { it.type != AssistantTimelineItemType.TEXT } || fallbackThoughts.isNotBlank() || toolEvents.isNotEmpty())) {
                        IconButton(onClick = { onExpandedChange(!expanded) }) {
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse activity" else "Expand activity", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (hasGatewaySource) {
                Text("Gateway", style = MaterialTheme.typography.labelMedium, color = ResponseSourceAppearance.gatewayForeground(MaterialTheme.colorScheme.primary, responseSurface), modifier = Modifier.padding(bottom = 4.dp))
            }
            beforeAnswer()
            key(answerIdentity) {
                CompositionLocalProvider(LocalAnswerSource provides documentSource) {
                    if (originalSource != null) {
                        BoundedOriginalAssistantDocument(answerIdentity, originalSource, proseColor, outputTrace, articleCards)
                    } else if (combinedManifest != null) {
                        BoundedAssistantDocument(answerIdentity, combinedManifest, loadCombinedBlocks, combinedWording, proseColor, outputTrace, articleCards)
                    }
                }
            }
            if (combinedManifest == null && originalSource == null && timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality?.status == dev.chungjungsoo.gptmobile.data.agent.CombinedAttributionRecovery.PENDING) {
                Text("Source attribution is pending. The saved answer is preserved, but its source links could not be reconstructed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            var textPartIndex = 0
            sourceItems.forEachIndexed { index, item ->
                // Retain the source timeline index for diagnostic part IDs while
                // excluding private process entries from the composition entirely.
                if (!effectiveExecutionTrace && item.type != AssistantTimelineItemType.TEXT) return@forEachIndexed
                // Debug visibility can remove process entries before an answer. Keep
                // prepared text and Markdown mounted when its process position changes.
                val rendererIndex = if (item.type == AssistantTimelineItemType.TEXT) textPartIndex++ else index
                key(if (item.type == AssistantTimelineItemType.TEXT) answerIdentity else contentIdentity, rendererIndex, item.type, item.toolSequence) {
                    when (item.type) {
                        AssistantTimelineItemType.THINKING -> if (expanded && showReasoning) {
                            Text(item.content, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AssistantTimelineItemType.TEXT -> if (combinedManifest == null && originalSource == null) {
                            CompositionLocalProvider(LocalAnswerSource provides item.responseSource) {
                                // Retain the previous committed display while the next streamed
                                // snapshot is prepared. A message key prevents cross-message reuse.
                                val latestBody = rememberUpdatedState(item.content)
                                val preparedDisplay by produceState(PreparedAssistantDisplay(), combinedWording != null) {
                                    snapshotFlow { latestBody.value }.prepareConflatedRender { body ->
                                        withContext(Dispatchers.Default) { prepareAssistantDisplay(body, combinedWording != null) }
                                    }.collect { value = it }
                                }
                                if (effectiveExecutionTrace && expanded && showReasoning && preparedDisplay.thinking.isNotBlank()) {
                                    Text(preparedDisplay.thinking, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                }
                                val citationText = preparedDisplay.citationText
                                val response = preparedDisplay.response
                                if (response.isNotBlank()) {
                                    if (isError) {
                                        Text(citationText, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                                    } else {
                                        if (response.length > dev.chungjungsoo.gptmobile.data.agent.CombinedDocuments.INLINE_CHARACTERS) {
                                            BoundedInlineAssistantDocument("$answerIdentity:$rendererIndex", response, combinedWording, proseColor, outputTrace?.copy(partId = "$index"), articleCards, streaming = isLoading)
                                        } else if (isLoading) {
                                            ChatMarkdown(adaptiveAnswerLength = totalAnswerLength, wording = combinedWording, outputTrace = outputTrace?.copy(partId = "$index"), proseColor = proseColor, memoryValues = recalledSources.map { it.value }, content = response, contentIdentity = "$answerIdentity:$rendererIndex", streaming = true, animateStreaming = animateResponse, modifier = Modifier.padding(vertical = 8.dp))
                                        } else {
                                            if (!splitForPassageIndicators && !debugSettings.researchFeature.articleCards) {
                                                // Standard and Combined replies without citation cards use
                                                // one Markdown tree: no paragraph splitting or extra work.
                                                ChatMarkdown(adaptiveAnswerLength = totalAnswerLength, wording = combinedWording, outputTrace = outputTrace?.copy(partId = "$index"), proseColor = proseColor, memoryValues = recalledSources.map { it.value }, content = response, contentIdentity = "$answerIdentity:$rendererIndex", streaming = false, modifier = Modifier.padding(vertical = 8.dp))
                                            } else {
                                                val prepared by produceState<PreparedResearchBlocks?>(null, response, splitForPassageIndicators) {
                                                    value = withContext(Dispatchers.Default) {
                                                        PreparedResearchBlocks(response, splitForPassageIndicators, researchResponseBlocks(response, splitParagraphs = splitForPassageIndicators))
                                                    }
                                                }
                                                val blocks = prepared?.takeIf { it.response == response && it.splitParagraphs == splitForPassageIndicators }?.blocks
                                                if (blocks == null) {
                                                    // Keep prose responsive while preparing citation boundaries.
                                                    ChatMarkdown(adaptiveAnswerLength = totalAnswerLength, wording = combinedWording, outputTrace = outputTrace?.copy(partId = "$index:pending"), proseColor = proseColor, memoryValues = recalledSources.map { it.value }, content = response, contentIdentity = "$answerIdentity:$rendererIndex:pending", streaming = false, modifier = Modifier.padding(vertical = 8.dp))
                                                } else {
                                                    blocks.forEachIndexed { blockIndex, block ->
                                                        ChatMarkdown(adaptiveAnswerLength = totalAnswerLength, wording = combinedWording, outputTrace = outputTrace?.copy(partId = "$index:$blockIndex"), proseColor = proseColor, memoryValues = recalledSources.map { it.value }, content = block.text, contentIdentity = "$answerIdentity:$rendererIndex:$blockIndex", streaming = false, modifier = Modifier.padding(vertical = 8.dp))
                                                        passageIndicators(block.text)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        AssistantTimelineItemType.TOOL -> if (expanded) {
                            events[item.toolSequence]?.let { event ->
                                InlineExecutionTrace(
                                    listOf(event),
                                    listOf(item),
                                    "$contentIdentity:$index",
                                    debugMode,
                                    remoteDelegation = items.take(index + 1).lastOrNull { it.delegationInvocationId != null }?.delegationRemote == true ||
                                        items.drop(index + 1).firstOrNull { it.delegationInvocationId != null }?.delegationRemote == true,
                                    debugSettings = debugSettings,
                                    debugMemorySources = debugMemorySources
                                )
                            }
                        }
                        AssistantTimelineItemType.NOTICE -> if (item.delegationInvocationId != null) {
                            if (debugMode && expanded) {
                                val reviewer = item.delegationProfile.orEmpty().endsWith(" · Reviewer", true)
                                val visible = if (reviewer) debugSettings.debugShowReviewerTrace else debugSettings.debugShowDelegationTrace
                                if (visible) DebugActivityCard(item.delegationProfile.orEmpty(), item.content, reviewer)
                            }
                        } else if (expanded && !item.statusSummary && !isContextDiagnostic(item.content)) {
                            if (item.recalledFacts.isNotEmpty()) {
                                InlineExecutionTrace(emptyList(), listOf(item), "$contentIdentity:$index", debugMode, debugSettings = debugSettings, debugMemorySources = debugMemorySources)
                            } else if (item.content.isNotBlank()) {
                                Text(item.content, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        AssistantTimelineItemType.LEGACY_ORDER -> Unit
                    }
                }
            }
            if (combinedManifest == null && originalSource == null && fallbackText.length <= dev.chungjungsoo.gptmobile.data.agent.CombinedDocuments.INLINE_CHARACTERS) articleCards(fallbackText)
        }
    }
}

internal fun isContextDiagnostic(value: String): Boolean = value.startsWith("Context estimate:", true) || value.startsWith("Context:", true) || value.startsWith("[telemetry]", true)
