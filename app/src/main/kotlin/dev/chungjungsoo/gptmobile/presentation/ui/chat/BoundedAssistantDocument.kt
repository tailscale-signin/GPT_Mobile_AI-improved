package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocuments
import dev.chungjungsoo.gptmobile.data.agent.CombinedMarkdownFrame
import dev.chungjungsoo.gptmobile.data.agent.CombinedMarkdownPaging
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The cache holds at most eight text pages, independent of document size. */
internal class CombinedDocumentPageCache(private val load: suspend (Int, Int) -> List<CombinedDocumentBlock>) {
    private val lock = Mutex()
    private val pages = LinkedHashMap<Int, CombinedDocumentBlock>(8, 0.75f, true)
    internal val retainedCharacters: Int get() = pages.values.sumOf { it.text.length }
    internal val retainedPages: Int get() = pages.size

    suspend fun get(index: Int): CombinedDocumentBlock = lock.withLock {
        pages[index]?.let { return@withLock it }
        val first = index / 4 * 4
        val loaded = load(first, 4)
        require(loaded.size <= 4 && loaded.map { it.sequence } == (first until first + loaded.size).toList()) { "COMBINED_DOCUMENT_PAGE_ORDER" }
        loaded.forEach { pages[it.sequence] = it }
        while (pages.size > 8) pages.remove(pages.keys.first())
        requireNotNull(pages[index]) { "COMBINED_DOCUMENT_PAGE_MISSING" }
    }
}

private data class DocumentPageState(val block: CombinedDocumentBlock? = null, val failed: Boolean = false)

/** One logical answer, bounded composition and parsing, complete document actions on its bubble. */
@Composable
internal fun BoundedAssistantDocument(
    identity: Any,
    reference: CombinedManifestReference,
    loadBlocks: suspend (CombinedManifestReference, Int, Int) -> List<CombinedDocumentBlock>,
    wording: CombinedWordingPresentation?,
    proseColor: Color?,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner? = null,
    articleCards: @Composable (String) -> Unit = {}
) {
    val currentLoader by rememberUpdatedState(loadBlocks)
    val cache = remember(reference.id) { CombinedDocumentPageCache { first, count -> currentLoader(reference, first, count) } }
    val page = remember<suspend (Int) -> CombinedDocumentBlock>(cache) { { index -> cache.get(index) } }
    DocumentViewport(identity, reference.blockCount, wording, proseColor, page, outputTrace?.copy(partId = "${reference.id}:${reference.revisionId}"), articleCards)
}

/** Contributor tabs share the bounded viewport; only the selected original is read. */
@Composable
internal fun BoundedOriginalAssistantDocument(
    identity: Any,
    reader: CombinedOriginalSourceReader,
    proseColor: Color?,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner? = null,
    articleCards: @Composable (String) -> Unit = {}
) {
    val count by produceState<Int?>(null, reader) { value = reader.count() }
    val cache = remember(reader) {
        CombinedDocumentPageCache { first, requested ->
            val trace = CombinedOperationTrace.begin("source_tab_load", reader.traceOwner.copy(blockId = "$first:${first + requested}"))
            try {
                reader.pages(first, requested)
            } finally {
                CombinedOperationTrace.end(trace)
            }
        }
    }
    val page = remember<suspend (Int) -> CombinedDocumentBlock>(cache) { { index -> cache.get(index) } }
    val total = count
    if (total == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        key(identity) {
            DocumentViewport(identity, total, null, proseColor, page, outputTrace?.copy(partId = "source:${reader.source.platformUid}:${reader.source.originalDocument?.payloadId}"), articleCards, reader::endTabTransition)
        }
    }
}

@Composable
internal fun BoundedInlineAssistantDocument(
    identity: Any,
    text: String,
    wording: CombinedWordingPresentation?,
    proseColor: Color?,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner? = null,
    articleCards: @Composable (String) -> Unit = {}
) {
    val ranges by produceState<List<CombinedMarkdownFrame>?>(null, text) {
        value = withContext(Dispatchers.Default) { CombinedMarkdownPaging.frames(text).toList() }
    }
    val ready = ranges
    if (ready == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        DocumentViewport(identity, ready.size, wording, proseColor, { index ->
            withContext(Dispatchers.Default) {
                val range = ready[index]
                val block = text.substring(range.start, range.end)
                CombinedDocumentBlock(index, range.start, block, researchHash(block), CombinedDocuments.slicePassages(text, range.start, range.end, wording?.passages.orEmpty()), range.openingFence, range.closingFence, range.tableHeader)
            }
        }, outputTrace, articleCards)
    }
}

@Composable
private fun DocumentViewport(
    identity: Any,
    count: Int,
    wording: CombinedWordingPresentation?,
    proseColor: Color?,
    page: suspend (Int) -> CombinedDocumentBlock,
    outputTrace: dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner?,
    articleCards: @Composable (String) -> Unit,
    onVisiblePage: (() -> Unit)? = null
) {
    if (count == 0) return
    val viewport = (LocalConfiguration.current.screenHeightDp * 0.7f).coerceAtLeast(240f).dp
    val state = rememberLazyListState()
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(viewport).testTag("combined_document")) {
        items(count = count, key = { "$identity:block:$it" }, contentType = { "answer-block" }) { index ->
            val loaded by produceState(DocumentPageState(), identity, index, page) {
                value = try {
                    DocumentPageState(page(index))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DocumentPageState(failed = true)
                }
            }
            val block = loaded.block
            when {
                block != null -> {
                    val prefix = remember(block) { block.openingFence + block.tableHeader }
                    val display = remember(block) { prefix + block.text + block.closingFence }
                    val presentation = remember(wording, block) {
                        wording?.copy(
                            sources = wording.sources.map { it.copy(content = "") },
                            fullAnswer = display,
                            passages = block.passages.map { passage ->
                                passage.copy(
                                    start = passage.start + prefix.length,
                                    end = passage.end + prefix.length,
                                    links = passage.links.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }
                                )
                            }
                        )
                    }
                    ChatMarkdown(content = display, contentIdentity = "$identity:$index:${block.digest}", wording = presentation, outputTrace = outputTrace?.copy(partId = "${outputTrace.partId}:block:$index:${block.digest}"), proseColor = proseColor, streaming = false, modifier = Modifier.padding(vertical = 8.dp), minimumPendingHeight = viewport, onContentDrawn = onVisiblePage)
                    articleCards(block.text)
                }
                loaded.failed -> Text("This part of the saved answer could not be opened.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                else -> Box(Modifier.fillMaxWidth().height(viewport)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}
