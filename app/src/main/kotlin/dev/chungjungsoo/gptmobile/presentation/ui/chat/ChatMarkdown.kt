package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.State as MarkdownParseState
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import dev.chungjungsoo.gptmobile.ui.component.SandboxedArtifactView
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes

private const val CLIPBOARD_LABEL_CODE = "code"
private const val STREAM_FADE_DURATION_MS = 1_000L
private const val STREAM_FADE_TICK_MS = 50L
private const val MARKDOWN_PREPARATION_SAMPLE_MS = 120L
private const val DISPLAY_MATH_PLACEHOLDER_PREFIX = "CHAT_MATH_DISPLAY_"
private const val DISPLAY_MATH_PLACEHOLDER_SUFFIX = "_TOKEN"
private const val DISPLAY_MATH_PLACEHOLDER_TEST_NONCE = "test"

/**
 * LRU cache for syntax highlighted code spans.
 * Tokenizing and parsing abstract syntax trees for programming languages (Python, Kotlin, JSON, etc.)
 * during chat scrolling or recomposition is computationally heavy and causes frame drops.
 * Caching the formatted AnnotatedString prevents repetitive parsing of identical code blocks.
 */
private val codeHighlightCache = object : LruCache<String, AnnotatedString>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: AnnotatedString): Int =
        (key.length.toLong() * 2 + value.length.toLong() * 2 + value.spanStyles.size * 64L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}

/** Drop derived rendering data under Android memory pressure; saved messages remain intact. */
internal fun clearChatRenderingCaches() {
    codeHighlightCache.evictAll()
    clearChatMarkdownParseCache()
}

private data class MarkdownRenderRequest(
    val content: String,
    val streaming: Boolean,
    val memoryValues: List<String>,
    val provenance: MarkdownProvenancePresentation?,
    val wording: CombinedWordingPresentation?,
    val outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner?
)

private data class PreparedChatMarkdown(
    val parsed: ParsedChatMarkdown = ParsedChatMarkdown(emptyList(), emptyList()),
    val markdown: String = "",
    val memoryRanges: List<IntRange> = emptyList(),
    val profileIndex: ProfileSpanIndex = ProfileSpanIndex(emptyList()),
    val contentDigest: String? = null
)

@OptIn(FlowPreview::class)
@Composable
fun ChatMarkdown(
    content: String,
    contentIdentity: Any = content,
    memoryValues: List<String> = emptyList(),
    highlightSentence: String? = null,
    highlightProgress: Float = 0f,
    streaming: Boolean = false,
    animateStreaming: Boolean = true,
    proseColor: Color? = null,
    provenance: MarkdownProvenancePresentation? = null,
    wording: CombinedWordingPresentation? = null,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner? = null,
    modifier: Modifier = Modifier,
    minimumPendingHeight: Dp = 0.dp,
    onContentDrawn: (() -> Unit)? = null
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val clipboardContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val interactionOwner = remember(contentIdentity) { MarkdownInteractionOwner() }
    DisposableEffect(interactionOwner) { onDispose { interactionOwner.dispose() } }
    val displayMathNonce = remember(contentIdentity) { UUID.randomUUID().toString().replace("-", "") }
    var attributionSelection by remember(contentIdentity) { mutableStateOf<Pair<String, Set<String>>?>(null) }
    attributionSelection?.let { (passage, owners) ->
        wording?.let { CombinedOriginalSourceDialog(passage, owners, it) { attributionSelection = null } }
    }
    var rendered by remember(contentIdentity) { mutableStateOf(PreparedChatMarkdown()) }
    var formattingFailed by remember(contentIdentity) { mutableStateOf(false) }
    var formattingAttempt by remember(contentIdentity) { mutableIntStateOf(0) }
    var lastTraceAt by remember(contentIdentity) { mutableLongStateOf(0L) }
    val latestRequest = rememberUpdatedState(
        MarkdownRenderRequest(content, streaming, memoryValues, provenance, wording, outputTrace)
    )
    LaunchedEffect(contentIdentity, formattingAttempt) {
        // Finish each in-flight render and retain only the latest pending delta.
        // Cancelling on every token starves slow renderers until generation ends.
        // Leaving this composition still cancels preparation through this effect.
        val requests = snapshotFlow { latestRequest.value }
        // Live deltas are rate-limited, but saved messages and final answers
        // render immediately rather than waiting for the next sample tick.
        merge(
            requests.filter { it.streaming && it.content.isNotBlank() }.take(1),
            requests.filter { it.streaming }.sample(MARKDOWN_PREPARATION_SAMPLE_MS),
            requests.filter { !it.streaming }
        ).filter { request ->
            // A sampled live delta must not supersede the final answer arriving
            // from the unsampled branch in the same composition identity.
            !request.streaming || latestRequest.value.streaming
        }.prepareConflatedRender { request ->
            val prepared = try {
                withTimeoutOrNull(30_000L) {
                    withContext(Dispatchers.Default) {
                        CombinedOperationTrace.measure("markdown_prepare", CombinedOperationOwner(runId = request.outputTrace?.runId, blockId = request.outputTrace?.partId)) {
                            val visibleSource = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(request.content)
                            val renderContext = currentCoroutineContext()
                            val parsed = parseChatMarkdown(visibleSource, cacheResult = !request.streaming, checkActive = { renderContext.ensureActive() })
                            // Rendering is sampled above; completed passage mappings can colour live output.
                            // Shared, invalid and unknown source declarations remain neutral.
                            val wantsWording = request.wording != null
                            val mapped = if (request.provenance != null || wantsWording) {
                                buildMappedMarkdown(request.content, visibleSource, parsed, displayMathNonce)
                            } else {
                                null
                            }
                            val combined = mapped?.first ?: buildCombinedMarkdown(parsed.blocks, displayMathNonce)
                            val profileSpans = if (request.provenance != null && mapped != null) {
                                mapped.second.exclusiveSpans(request.content, combined, request.provenance.validated, request.provenance.corpusDigest, request.provenance.policyRevision)
                            } else if (wantsWording && mapped != null) {
                                mapped.second.wordingSpans(request.content, combined, requireNotNull(request.wording).sources, request.wording.passages, request.wording.fullAnswer, includeShared = true)
                            } else {
                                emptyList()
                            }
                            if (wantsWording && !request.streaming && CombinedAttributionDiagnostics.changed("mapping:${request.outputTrace?.runId}:$contentIdentity", dev.chungjungsoo.gptmobile.data.research.researchHash(request.content + profileSpans))) {
                                val zeroReason = when {
                                    profileSpans.isNotEmpty() -> "NONE"
                                    request.content.isBlank() -> "EMPTY_RENDER_INPUT"
                                    request.wording?.sources.isNullOrEmpty() -> "NO_CONTRIBUTOR_HEADERS"
                                    mapped?.second?.runs.isNullOrEmpty() -> "NO_VERBATIM_MARKDOWN_RUNS"
                                    request.wording?.sources.orEmpty().all { it.content.isBlank() } && request.wording?.passages.isNullOrEmpty() -> "SOURCE_MAP_UNAVAILABLE"
                                    else -> "NO_CONSERVATIVE_LINK_MATCH"
                                }
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedPalette", "DISPLAY_SPAN_MAPPING · run=${request.outputTrace?.runId.orEmpty()} · part=${request.outputTrace?.partId.orEmpty()} · contentIdentityDigest=${dev.chungjungsoo.gptmobile.data.research.researchHash(contentIdentity.toString())} · bodyDigest=${dev.chungjungsoo.gptmobile.data.research.researchHash(request.content)} · parser=$ATTRIBUTION_MARKDOWN_PARSER_VERSION · zeroLinkReason=$zeroReason · uniqueSpans=${profileSpans.count { it.profileUids.size == 1 }} · sharedSpans=${profileSpans.count { it.profileUids.size > 1 }} · linkedCharacters=${profileSpans.sumOf { it.end - it.start }} · represented=${profileSpans.flatMap { it.profileUids }.distinct().take(32)} · palette=PERSISTED_PROFILE_HUE · factualVerification=false")
                            }
                            PreparedChatMarkdown(parsed, combined, memoryHighlightRanges(combined, request.memoryValues), ProfileSpanIndex(profileSpans), dev.chungjungsoo.gptmobile.data.research.researchHash(request.content))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            request to prepared
        }.collect { (request, prepared) ->
            formattingFailed = prepared == null
            if (prepared == null) return@collect
            rendered = prepared
            val now = android.os.SystemClock.elapsedRealtime()
            val traceJournal = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.takeIf { it.enabled }
            val trace = request.outputTrace
            if (trace != null && traceJournal != null && (!request.streaming || now - lastTraceAt >= 250)) {
                lastTraceAt = now
                withContext(Dispatchers.Default) {
                    traceJournal.traceOutput(trace.runId, dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.RENDER_INPUT, request.content, fence = trace.fence, representation = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTextRepresentation.DISPLAY_FILTERED_MARKDOWN_UTF8, partId = trace.partId)
                    traceJournal.traceOutput(trace.runId, dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.RENDER_TRANSFORM, prepared.markdown, fence = trace.fence, representation = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTextRepresentation.MATH_PLACEHOLDER_MARKDOWN_UTF8, partId = trace.partId)
                }
            }
        }
    }
    val parsed = rendered.parsed
    val firstDrawn = remember(outputTrace?.runId, outputTrace?.fence) { java.util.concurrent.atomic.AtomicBoolean() }
    val combinedMarkdown = rendered.markdown
    val renderedDigest = rendered.contentDigest
    val profileIndex = rendered.profileIndex
    SideEffect { interactionOwner.update(renderedDigest) }
    val memoryRanges = rendered.memoryRanges
    val inlineMathByPlaceholder = remember(parsed.inlineMath) {
        parsed.inlineMath.associateBy { it.placeholder }
    }
    val displayMathByPlaceholder = remember(parsed.blocks, displayMathNonce) {
        parsed.blocks
            .filterIsInstance<ChatMarkdownBlock.DisplayMath>()
            .mapIndexed { index, block ->
                createDisplayMathPlaceholder(index, displayMathNonce) to block
            }
            .toMap()
    }
    val normalTextColor = MaterialTheme.colorScheme.onSurface
    // One timestamp per arriving chunk, rather than one boxed Long per character.
    val arrivalSegments = remember(contentIdentity) { mutableStateListOf<Pair<Int, Long>>() }
    var arrivalLength by remember(contentIdentity) { mutableIntStateOf(0) }
    val hasStreamed = remember(contentIdentity) { mutableStateOf(streaming && animateStreaming) }
    val fadeClock = remember(contentIdentity) { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(combinedMarkdown.length, streaming, animateStreaming) {
        if (streaming && animateStreaming) hasStreamed.value = true
        val now = android.os.SystemClock.uptimeMillis()
        if (combinedMarkdown.length < arrivalLength) {
            arrivalSegments.clear()
            arrivalLength = 0
        }
        if (combinedMarkdown.length > arrivalLength) {
            if (hasStreamed.value) arrivalSegments.add(arrivalLength to now)
            arrivalLength = combinedMarkdown.length
        }
        val animating = hasStreamed.value && arrivalSegments.lastOrNull()?.let { now - it.second < STREAM_FADE_DURATION_MS } == true
        val releaseEffect = if (animating) dev.chungjungsoo.gptmobile.data.diagnostics.RenderingEffectDiagnostics.enter(dev.chungjungsoo.gptmobile.data.diagnostics.RenderingEffectDiagnostics.Effect.STREAMING_FADE) else null
        try {
            while (hasStreamed.value && arrivalSegments.lastOrNull()?.let { fadeClock.longValue - it.second < STREAM_FADE_DURATION_MS } == true) {
                fadeClock.longValue = android.os.SystemClock.uptimeMillis()
                // Settled text is fully opaque and needs no per-frame history scan.
                while (arrivalSegments.size > 1 && fadeClock.longValue - arrivalSegments[1].second >= STREAM_FADE_DURATION_MS) {
                    arrivalSegments.removeAt(0)
                }
                delay(STREAM_FADE_TICK_MS)
            }
        } finally {
            releaseEffect?.invoke()
        }
    }
    val targetYellow = SuggestionHighlightManager.HIGHLIGHT_YELLOW
    val targetBg = SuggestionHighlightManager.HIGHLIGHT_TRANSLUCENT_BG

    val sourceLinkRanges = remember(combinedMarkdown) {
        Regex("""\[[^\]\n]*]\([^)\n]+\)|https?://[^\s<>]+""").findAll(combinedMarkdown).map { it.range }.toList()
    }
    val annotator = remember(sourceLinkRanges, memoryRanges, rendered.profileIndex, provenance, wording, proseColor, inlineMathByPlaceholder, highlightSentence, highlightProgress, normalTextColor, interactionOwner, renderedDigest) {
        markdownAnnotator { source, child ->
            val text = source.substring(child.startOffset, child.endOffset)
            val hasMath = containsInlineMathPlaceholder(text)
            val hasSentenceHighlight = !highlightSentence.isNullOrBlank() &&
                highlightProgress > 0.01f &&
                text.contains(highlightSentence, ignoreCase = true)

            val streamVisibleText = streamVisibleMarkdownTokenText(child.type, text)

            val memorySpans = memoryRanges.filter { it.first < child.endOffset && it.last >= child.startOffset }
            val profileSpans = profileIndex.overlapping(child.startOffset, child.endOffset)
            if (!hasSentenceHighlight && streamVisibleText != null && (proseColor != null || profileSpans.isNotEmpty())) {
                appendTextWithInlineMath(this, streamVisibleText, inlineMathByPlaceholder) { from, to, outputStart ->
                    val startOffset = child.startOffset + from
                    val endOffset = child.startOffset + to
                    proseColor?.let { addStyle(SpanStyle(color = it), outputStart, outputStart + to - from) }
                    profileSpans.forEach { span ->
                        val start = maxOf(startOffset, span.start)
                        val end = minOf(endOffset, span.end)
                        val color = (provenance?.profileColors ?: wording?.profileColors)?.get(span.profileUid)
                        if (start < end) {
                            val from = outputStart + start - startOffset
                            val to = outputStart + end - startOffset
                            if (color != null && span.profileUids.size == 1) addStyle(SpanStyle(color = color), from, to)
                            // Keep existing citation/URL links in charge of their own tap targets.
                            if (wording != null && sourceLinkRanges.none { it.first < child.endOffset && it.last >= child.startOffset }) {
                                addLink(
                                    androidx.compose.ui.text.LinkAnnotation.Clickable("combined:${span.start}:${span.end}") {
                                        if (interactionOwner.accepts(renderedDigest)) attributionSelection = combinedMarkdown.substring(span.start, span.end) to span.profileUids
                                    },
                                    from,
                                    to
                                )
                            }
                        }
                    }
                }
                true
            } else if (!hasMath && streamVisibleText != null && memorySpans.isNotEmpty()) {
                val start = length
                append(streamVisibleText)
                memorySpans.forEach { range ->
                    val from = maxOf(range.first, child.startOffset)
                    val to = minOf(range.last + 1, child.endOffset)
                    if (to > from) {
                        addStyle(
                            SpanStyle(color = DebugMemoryPink),
                            start + (from - child.startOffset).coerceAtMost(streamVisibleText.length),
                            start + (to - child.startOffset).coerceAtMost(streamVisibleText.length)
                        )
                    }
                }
                true
            } else if (!hasMath && !hasSentenceHighlight) {
                false
            } else {
                if (hasSentenceHighlight && !hasMath) {
                    val sIndex = text.indexOf(highlightSentence!!, ignoreCase = true)
                    if (sIndex != -1) {
                        val interpolatedColor = lerp(normalTextColor, targetYellow, highlightProgress.coerceIn(0f, 1f))
                        val interpolatedBg = Color.Transparent.copy(alpha = 0f).let {
                            lerp(it, targetBg, highlightProgress.coerceIn(0f, 1f))
                        }
                        val interpolatedWeight = SuggestionHighlightManager.interpolateFontWeight(highlightProgress)

                        if (sIndex > 0) {
                            append(text.substring(0, sIndex))
                        }
                        val startSpan = length
                        append(text.substring(sIndex, sIndex + highlightSentence.length))
                        val endSpan = length
                        addStyle(
                            style = SpanStyle(
                                color = interpolatedColor,
                                background = interpolatedBg,
                                fontWeight = interpolatedWeight
                            ),
                            start = startSpan,
                            end = endSpan
                        )
                        if (sIndex + highlightSentence.length < text.length) {
                            append(text.substring(sIndex + highlightSentence.length))
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    appendTextWithInlineMath(this, text, inlineMathByPlaceholder)
                    true
                }
            }
        }
    }
    val inlineContent = remember(parsed.inlineMath) {
        parsed.inlineMath.associate { token ->
            token.placeholder to InlineTextContent(
                placeholder = Placeholder(
                    width = inlineMathWidth(token.tex),
                    height = inlineMathHeight(token.tex),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                InlineMathView(token.tex)
            }
        }
    }
    val copyCodeToClipboard: (String) -> Unit = remember(clipboardContext, scope, interactionOwner) {
        { code ->
            if (interactionOwner.active) {
                scope.launch {
                    dev.chungjungsoo.gptmobile.util.SafeClipboard.copy(clipboardContext, code, CLIPBOARD_LABEL_CODE)
                }
            }
        }
    }
    val components = remember(
        isDarkTheme,
        copyCodeToClipboard,
        displayMathByPlaceholder,
        annotator,
        hasStreamed,
        fadeClock,
        arrivalSegments,
        profileIndex,
        wording,
        combinedMarkdown,
        interactionOwner,
        renderedDigest
    ) {
        // Read the animation clock only in the graphics layer. A fade tick must not
        // rebuild annotations, reparse Markdown, or measure settled paragraphs.
        fun Modifier.streamFade(startOffset: Int): Modifier = graphicsLayer {
            alpha = if (hasStreamed.value) streamAlphaAtOffset(startOffset, arrivalSegments, fadeClock.longValue) else 1f
        }

        markdownComponents(
            table = { model ->
                val settings = annotatorSettings(
                    LocalMarkdownTypography.current.textLink,
                    LocalMarkdownTypography.current.inlineCode.toSpanStyle(),
                    annotator,
                    LocalReferenceLinkHandler.current,
                    LocalUriHandler.current,
                    null
                )
                Column(modifier = Modifier.streamFade(model.node.startOffset)) {
                    MarkdownTable(
                        content = model.content,
                        node = model.node,
                        style = model.typography.table,
                        annotatorSettings = settings,
                        headerBlock = { tableContent, header, tableWidth, style ->
                            MarkdownTableHeader(
                                tableContent,
                                header,
                                tableWidth,
                                style,
                                maxLines = Int.MAX_VALUE,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                                annotatorSettings = settings
                            )
                        },
                        rowBlock = { tableContent, row, tableWidth, style ->
                            MarkdownTableRow(
                                tableContent,
                                row,
                                tableWidth,
                                style,
                                maxLines = Int.MAX_VALUE,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                                annotatorSettings = settings
                            )
                        }
                    )
                    CombinedSharedSourceMarkers(combinedMarkdown, profileIndex.overlapping(model.node.startOffset, model.node.endOffset), wording) { passage, owners -> if (interactionOwner.accepts(renderedDigest)) attributionSelection = passage to owners }
                }
            },
            codeBlock = {
                Box(modifier = Modifier.streamFade(it.node.startOffset)) {
                    MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style ->
                        val cleanLang = language?.trim()?.lowercase()
                        val isHtmlOrSvg = cleanLang == "html" || cleanLang == "htm" || cleanLang == "svg"
                        if (isHtmlOrSvg) {
                            SandboxedArtifactView(
                                title = cleanLang.uppercase(),
                                content = code,
                                isHtmlOrSvg = true
                            )
                        } else {
                            CodeBlockWithCopy(
                                code = code,
                                language = language,
                                onCopyCode = copyCodeToClipboard
                            ) {
                                HighlightedCodeContent(
                                    code = code,
                                    language = language,
                                    style = style,
                                    isDarkTheme = isDarkTheme
                                )
                            }
                        }
                    }
                }
            },
            codeFence = {
                Box(modifier = Modifier.streamFade(it.node.startOffset)) {
                    MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
                        val cleanLang = language?.trim()?.lowercase()
                        val isHtmlOrSvg = cleanLang == "html" || cleanLang == "htm" || cleanLang == "svg"
                        if (isHtmlOrSvg) {
                            SandboxedArtifactView(
                                title = cleanLang.uppercase(),
                                content = code,
                                isHtmlOrSvg = true
                            )
                        } else {
                            CodeBlockWithCopy(
                                code = code,
                                language = language,
                                onCopyCode = copyCodeToClipboard
                            ) {
                                HighlightedCodeContent(
                                    code = code,
                                    language = language,
                                    style = style,
                                    isDarkTheme = isDarkTheme
                                )
                            }
                        }
                    }
                }
            },
            paragraph = { model ->
                val paragraphText = extractNodeText(model.content, model.node).trim()
                val displayMathBlocks = resolveDisplayMathParagraph(
                    paragraphText = paragraphText,
                    displayMathByPlaceholder = displayMathByPlaceholder
                )
                if (displayMathBlocks != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .streamFade(model.node.startOffset)
                    ) {
                        displayMathBlocks.forEach { displayMath ->
                            DisplayMathView(
                                tex = displayMath.tex,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                            )
                        }
                    }
                } else {
                    Column(Modifier.streamFade(model.node.startOffset)) {
                        DefaultParagraph(model.content, model.node, model.typography.paragraph, annotator)
                        CombinedSharedSourceMarkers(combinedMarkdown, profileIndex.overlapping(model.node.startOffset, model.node.endOffset), wording) { passage, owners -> if (interactionOwner.accepts(renderedDigest)) attributionSelection = passage to owners }
                    }
                }
            }
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val externalHandler = LocalUriHandler.current
    val sourceHandler = remember(context, externalHandler, interactionOwner) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (!interactionOwner.active) return
                val parsedUri = android.net.Uri.parse(uri)
                if (parsedUri.scheme == "gptmobile" && parsedUri.host == "knowledge") {
                    context.startActivity(android.content.Intent(context, dev.chungjungsoo.gptmobile.presentation.ui.setting.KnowledgeSourceActivity::class.java).setData(parsedUri))
                } else if (parsedUri.scheme == "gptmobile" && parsedUri.host == "media") {
                    context.startActivity(android.content.Intent(context, dev.chungjungsoo.gptmobile.presentation.ui.setting.McpMediaActivity::class.java).setData(parsedUri))
                } else if (parsedUri.scheme in setOf("https", "http", "mailto")) {
                    externalHandler.openUri(uri)
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalUriHandler provides sourceHandler) {
        key(contentIdentity, highlightSentence, formattingAttempt) {
            val markdownState = rememberMarkdownState(
                content = combinedMarkdown,
                retainState = true
            )
            val parserState by markdownState.state.collectAsStateWithLifecycle()
            // A saved page has two asynchronous preparation steps. Keep its lazy-list
            // placeholder until this renderer has parsed the matching prepared text;
            // an empty/retained parser state must not expose the next document pages.
            val awaitingContent = rendered.contentDigest == null ||
                when (val status = parserState) {
                    is MarkdownParseState.Loading -> true
                    is MarkdownParseState.Success -> status.content != combinedMarkdown
                    else -> false
                }
            var parserTimedOut by remember(contentIdentity, renderedDigest) { mutableStateOf(false) }
            LaunchedEffect(awaitingContent, renderedDigest) {
                if (awaitingContent) {
                    delay(30_000L)
                    parserTimedOut = true
                } else {
                    parserTimedOut = false
                }
            }
            val parserFailed = renderedDigest != null && parserState !is MarkdownParseState.Loading && parserState !is MarkdownParseState.Success
            val animations = markdownAnimations(animateTextSize = { this })
            val layoutOwner = remember(outputTrace, rendered.contentDigest) { CombinedOperationOwner(runId = outputTrace?.runId, blockId = outputTrace?.partId, digest = rendered.contentDigest) }

            Box(Modifier.heightIn(min = if (awaitingContent) minimumPendingHeight else 0.dp), propagateMinConstraints = true) {
                if (formattingFailed || parserTimedOut || parserFailed) {
                    Column(modifier) {
                        Text(
                            dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(content),
                            modifier = Modifier.drawWithContent {
                                drawContent()
                                if (content.isNotBlank()) onContentDrawn?.invoke()
                            }
                        )
                        TextButton(onClick = { formattingAttempt++ }) { Text(stringResource(R.string.chat_retry_formatting)) }
                    }
                } else {
                    Markdown(
                        markdownState = markdownState,
                        inlineContent = markdownInlineContent(inlineContent),
                        annotator = annotator,
                        components = components,
                        typography = chatMarkdownTypography(),
                        animations = animations,
                        modifier = modifier.layout { measurable, constraints ->
                            val measured = CombinedOperationTrace.measure("text_measure", layoutOwner) { measurable.measure(constraints) }
                            layout(measured.width, measured.height) {
                                CombinedOperationTrace.measure("text_layout", layoutOwner) { measured.placeRelative(0, 0) }
                            }
                        }.drawWithContent {
                            drawContent()
                            if (interactionOwner.accepts(renderedDigest) && !awaitingContent && parserState is MarkdownParseState.Success && combinedMarkdown.isNotBlank()) {
                                onContentDrawn?.invoke()
                                if (outputTrace != null && firstDrawn.compareAndSet(false, true)) dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(outputTrace.runId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_VISIBLE_TEXT, fence = outputTrace.fence)
                            }
                        }
                    )
                }
            }
        }
    }
}

internal fun streamVisibleMarkdownTokenText(
    type: IElementType,
    rawText: String
): String? = when (type) {
    MarkdownTokenTypes.TEXT -> rawText
    MarkdownTokenTypes.SINGLE_QUOTE -> "'"
    MarkdownTokenTypes.DOUBLE_QUOTE -> "\""
    MarkdownTokenTypes.LPAREN -> "("
    MarkdownTokenTypes.RPAREN -> ")"
    MarkdownTokenTypes.LBRACKET -> "["
    MarkdownTokenTypes.RBRACKET -> "]"
    MarkdownTokenTypes.COLON -> ":"
    MarkdownTokenTypes.EXCLAMATION_MARK -> "!"
    MarkdownTokenTypes.BACKTICK -> "`"
    else -> null
}

private fun streamAlphaAtOffset(
    sourceOffset: Int,
    arrivalSegments: List<Pair<Int, Long>>,
    nowMs: Long
): Float {
    val segmentIndex = arrivalSegments.indexOfLast { (offset, _) -> offset <= sourceOffset }
    if (segmentIndex < 0) return 1f
    return streamAlpha(arrivalSegments[segmentIndex].second, nowMs)
}

private fun streamAlpha(arrivedAtMs: Long, nowMs: Long): Float =
    ((nowMs - arrivedAtMs).coerceAtLeast(0L) / STREAM_FADE_DURATION_MS.toFloat())
        .coerceIn(0f, 1f)

@Composable
private fun CodeBlockWithCopy(
    code: String,
    language: String?,
    onCopyCode: (String) -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.padding(end = 12.dp),
                    text = language?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    modifier = Modifier.heightIn(min = 32.dp),
                    onClick = { onCopyCode(code) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.copy_code),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun HighlightedCodeContent(
    code: String,
    language: String?,
    style: TextStyle,
    isDarkTheme: Boolean
) {
    val cacheKey = "$isDarkTheme:$language:$code"
    val cached = codeHighlightCache.get(cacheKey)

    val highlightedCode by produceState(
        initialValue = cached ?: AnnotatedString(code),
        key1 = cacheKey
    ) {
        if (cached == null) {
            val result = withContext(Dispatchers.Default) {
                // Builders are mutable: never share one across parallel code blocks.
                if (code.length > 128 * 1024) {
                    AnnotatedString(code)
                } else {
                    buildHighlightedAnnotatedString(code, language, Highlights.Builder().theme(SyntaxThemes.atom(isDarkTheme)))
                }
            }
            if (code.length <= 128 * 1024) codeHighlightCache.put(cacheKey, result)
            value = result
        }
    }

    Text(
        text = highlightedCode,
        style = style,
        softWrap = false,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

private fun buildHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder
): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    val codeHighlights = highlightsBuilder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()

    return AnnotatedString.Builder(code).apply {
        codeHighlights.forEach { highlight ->
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = androidx.compose.ui.graphics.Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(
                style = spanStyle,
                start = highlight.location.start,
                end = highlight.location.end
            )
        }
    }.toAnnotatedString()
}

private fun appendTextWithInlineMath(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    text: String,
    inlineMathByPlaceholder: Map<String, InlineMathToken>,
    onTextAppended: ((Int, Int, Int) -> Unit)? = null
) {
    fun appendPlain(start: Int, end: Int) {
        val outputStart = builder.length
        builder.append(text.substring(start, end))
        onTextAppended?.invoke(start, end, outputStart)
    }
    var cursor = 0
    while (cursor < text.length) {
        val nextToken = inlineMathByPlaceholder.keys
            .mapNotNull { placeholder ->
                val start = text.indexOf(placeholder, cursor)
                if (start == -1) null else placeholder to start
            }
            .minByOrNull { it.second }

        if (nextToken == null) {
            appendPlain(cursor, text.length)
            return
        }

        val (placeholder, start) = nextToken
        if (start > cursor) {
            appendPlain(cursor, start)
        }
        builder.appendInlineContent(placeholder, "[math]")
        cursor = start + placeholder.length
    }
}

private fun inlineMathWidth(tex: String) = (tex.length.coerceIn(2, 24) * 0.55f).em

private fun inlineMathHeight(tex: String) = when {
    tex.containsDisplaySizedMath() -> 3.2.em
    tex.containsSuperscriptOrSubscriptMath() -> 2.1.em
    else -> 1.6.em
}

private fun resolveDisplayMathParagraph(
    paragraphText: String,
    displayMathByPlaceholder: Map<String, ChatMarkdownBlock.DisplayMath>
): List<ChatMarkdownBlock.DisplayMath>? {
    if (paragraphText.isEmpty()) return null

    val placeholders = displayMathByPlaceholder.keys
        .filter(paragraphText::contains)
    if (placeholders.isEmpty()) return null

    val nonPlaceholderText = placeholders.fold(paragraphText) { current, placeholder ->
        current.replace(placeholder, " ")
    }
    if (nonPlaceholderText.isNotBlank()) return null

    return placeholders.mapNotNull(displayMathByPlaceholder::get)
}

private fun String.containsDisplaySizedMath(): Boolean = listOf(
    "\\frac",
    "\\dfrac",
    "\\tfrac",
    "\\sum",
    "\\prod",
    "\\int",
    "\\oint",
    "\\lim",
    "\\begin",
    "\\left",
    "\\right",
    "\\over"
).any(::contains)

private fun String.containsSuperscriptOrSubscriptMath(): Boolean = contains('^') || contains('_')

internal fun buildCombinedMarkdown(
    blocks: List<ChatMarkdownBlock>,
    nonce: String = DISPLAY_MATH_PLACEHOLDER_TEST_NONCE,
    onMarkdownBlock: ((Int, Int) -> Unit)? = null
): String = buildString {
    var displayMathIndex = 0
    blocks.forEachIndexed { blockIndex, block ->
        when (block) {
            is ChatMarkdownBlock.Markdown -> {
                onMarkdownBlock?.invoke(blockIndex, length)
                append(block.content)
            }

            is ChatMarkdownBlock.DisplayMath -> appendDisplayMathPlaceholder(
                createDisplayMathPlaceholder(displayMathIndex++, nonce)
            )
        }
    }
}

private fun StringBuilder.appendDisplayMathPlaceholder(placeholder: String) {
    val linePrefix = currentLinePrefix().orEmpty()
    if (!isEmpty() && !endsWithBlankLine()) {
        if (last() != '\n') {
            appendLine()
        }
        appendLine()
    }
    if (linePrefix.isNotEmpty()) {
        append(linePrefix)
    }
    append(placeholder)
    appendLine()
    appendLine()
}

private fun StringBuilder.endsWithBlankLine(): Boolean = length >= 2 && this[length - 1] == '\n' && this[length - 2] == '\n'

private fun StringBuilder.currentLinePrefix(): String? {
    val lineStart = lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    if (lineStart == length) return ""

    val currentLine = substring(lineStart, length)
    return currentLine.takeIf { line ->
        line.all { character ->
            character == ' ' || character == '\t' || character == '>'
        }
    }
}

private fun createDisplayMathPlaceholder(
    index: Int,
    nonce: String
): String = "\uE000$DISPLAY_MATH_PLACEHOLDER_PREFIX${nonce}_$index$DISPLAY_MATH_PLACEHOLDER_SUFFIX\uE001"

@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineMedium,
    h2 = MaterialTheme.typography.headlineSmall,
    h3 = MaterialTheme.typography.titleLarge,
    h4 = MaterialTheme.typography.titleMedium,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.labelLarge,
    text = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium
)

@Composable
private fun DefaultParagraph(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
    style: TextStyle,
    annotator: MarkdownAnnotator
) {
    MarkdownParagraph(
        content,
        node,
        Modifier,
        style,
        annotatorSettings(
            LocalMarkdownTypography.current.textLink,
            LocalMarkdownTypography.current.inlineCode.toSpanStyle(),
            annotator,
            LocalReferenceLinkHandler.current,
            LocalUriHandler.current,
            null
        )
    )
}

private fun extractNodeText(
    content: String,
    node: org.intellij.markdown.ast.ASTNode
): String = content.substring(node.startOffset, node.endOffset)
