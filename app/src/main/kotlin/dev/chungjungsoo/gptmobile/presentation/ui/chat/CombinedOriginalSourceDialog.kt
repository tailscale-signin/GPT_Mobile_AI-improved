package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedClaimClauses
import dev.chungjungsoo.gptmobile.data.agent.CombinedClaimMatcher
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedPayload
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun combinedInspectionLoader(
    reference: CombinedManifestReference?,
    load: suspend (CombinedManifestReference, CombinedModelResponse, Int, Int) -> List<CombinedDocumentBlock>
): (suspend (CombinedModelResponse, Int, Int) -> List<CombinedDocumentBlock>)? {
    if (reference == null) return null
    return { source, first, count -> load(reference, source, first, count) }
}

/** One current page plus a small range index; no full durable body is materialized. */
class CombinedOriginalSourceReader internal constructor(
    internal val source: CombinedModelResponse,
    private val load: (suspend (CombinedModelResponse, Int, Int) -> List<CombinedDocumentBlock>)?,
    internal val traceOwner: CombinedOperationOwner = CombinedOperationOwner(runId = source.originalDocument?.runId, digest = source.originalDocument?.digest, profileUid = source.platformUid)
) {
    private var transition: CombinedOperationTrace.Token? = null

    @Synchronized internal fun beginTabTransition() {
        CombinedOperationTrace.end(transition)
        transition = CombinedOperationTrace.begin("source_tab_transition", traceOwner)
    }

    @Synchronized internal fun endTabTransition() {
        val current = transition
        transition = null
        CombinedOperationTrace.end(current)
    }

    private val ranges by lazy { CombinedPayload.pages(source.content).map { (start, text) -> start until start + text.length }.toList() }
    suspend fun count(): Int = withContext(Dispatchers.Default) { source.originalDocument?.blockCount ?: ranges.size }
    suspend fun page(index: Int): CombinedDocumentBlock = pages(index, 1).single()

    suspend fun pages(first: Int, requested: Int): List<CombinedDocumentBlock> = withContext(Dispatchers.Default) {
        val total = count()
        require(first in 0 until total && requested in 1..8) { "Original reply page is unavailable" }
        val size = minOf(requested, total - first)
        val reference = source.originalDocument
        if (reference != null) {
            val result = requireNotNull(load) { "Original reply loader is unavailable" }.invoke(source, first, size)
            require(result.size == size && result.map { it.sequence } == (first until first + size).toList()) { "Original reply page order failed validation" }
            require(result.all { it.text.length <= CombinedPayload.PAGE_CHARACTERS && researchHash(it.text) == it.digest }) { "Original reply page failed validation" }
            result
        } else {
            (first until first + size).map { index ->
                val range = ranges[index]
                val text = source.content.substring(range.first, range.last + 1)
                CombinedDocumentBlock(index, range.first, text, researchHash(text), emptyList())
            }
        }
    }

    /** Full materialization is reserved for an explicit copy or select-text action. */
    suspend fun readAll(): String = withContext(Dispatchers.Default) {
        val reference = source.originalDocument ?: return@withContext source.content
        val result = buildString(reference.length) {
            var first = 0
            while (first < reference.blockCount) {
                currentCoroutineContext().ensureActive()
                val blocks = pages(first, 4)
                blocks.forEach { block ->
                    require(block.start == length) { "Original reply position failed validation" }
                    append(block.text)
                }
                first += blocks.size
            }
        }
        require(result.length == reference.length && researchHash(result) == reference.digest) { "Original reply content failed validation" }
        result
    }
    suspend fun relatedPage(passage: String): Int? = withContext(Dispatchers.Default) {
        val needle = passage.trim()
        if (needle.length < 3) return@withContext null
        for (index in 0 until count()) {
            currentCoroutineContext().ensureActive()
            val text = page(index).text
            if (text.contains(needle, ignoreCase = true) || CombinedClaimClauses.spans(text).any { CombinedClaimMatcher.match(needle, it.text) >= CombinedClaimMatcher.Match.PARAPHRASE }) return@withContext index
        }
        null
    }
}

@Composable
internal fun CombinedSharedSourceMarkers(
    markdown: String,
    spans: List<MappedProfileSpan>,
    wording: CombinedWordingPresentation?,
    onInspect: (String, Set<String>) -> Unit
) {
    if (wording == null) return
    val sources = wording.inspectionSources.associateBy { it.platformUid }
    val fallbackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val shared = spans.filter { it.profileUids.size > 1 && it.start >= 0 && it.end <= markdown.length }.groupBy { it.profileUids }
    Column {
        shared.forEach { (owners, parts) ->
            val names = owners.sorted().map { sources[it]?.platformName ?: it }
            val passage = markdown.substring(parts.minOf { it.start }, parts.maxOf { it.end })
            val label = "Shared contribution from ${names.joinToString(" and ")}. Inspect original replies."
            TextButton(onClick = { onInspect(passage, owners) }, modifier = Modifier.semantics { contentDescription = label }) {
                Text(
                    buildAnnotatedString {
                        append("Shared · ")
                        owners.sorted().forEachIndexed { index, uid ->
                            if (index > 0) append(" · ")
                            withStyle(SpanStyle(color = wording.profileColors[uid] ?: fallbackColor)) {
                                wording.profileMarkers[uid]?.let { append("[$it] ") }
                                append(sources[uid]?.platformName ?: uid)
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private data class OriginalSourcePage(val block: CombinedDocumentBlock? = null, val total: Int = 0, val failed: Boolean = false, val reader: CombinedOriginalSourceReader? = null, val index: Int = -1)

@Composable
internal fun CombinedOriginalSourceDialog(passage: String, owners: Set<String>, wording: CombinedWordingPresentation, onDismiss: () -> Unit) {
    val sources = wording.inspectionSources.filter { it.platformUid in owners }
    var selectedUid by remember(passage, owners, sources) { mutableStateOf(sources.firstOrNull()?.platformUid) }
    val source = sources.firstOrNull { it.platformUid == selectedUid }
    val reader = remember(source, wording.loadSourceBlocks) { source?.let { CombinedOriginalSourceReader(it, wording.loadSourceBlocks) } }
    var page by remember(reader) { mutableIntStateOf(0) }
    var findRequest by remember(reader) { mutableIntStateOf(0) }
    var searching by remember(reader) { mutableStateOf(false) }
    var findMessage by remember(reader) { mutableStateOf<String?>(null) }
    val loadedState by produceState(OriginalSourcePage(), reader, page) {
        value = OriginalSourcePage()
        value = (
            try {
                val total = reader?.count() ?: 0
                if (total == 0) OriginalSourcePage(failed = true) else OriginalSourcePage(requireNotNull(reader).page(page), total)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OriginalSourcePage(failed = true)
            }
            ).copy(reader = reader, index = page)
    }
    val loaded = loadedState.takeIf { it.reader === reader && it.index == page } ?: OriginalSourcePage()
    LaunchedEffect(reader, findRequest) {
        if (findRequest == 0 || reader == null) return@LaunchedEffect
        searching = true
        try {
            val found = reader.relatedPage(passage)
            if (found != null) page = found
            findMessage = if (found == null) "No matching wording was found. You can still read every retained page." else "Related wording is on page ${found + 1}."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            findMessage = "Some original pages could not be opened. The retained reply has not been replaced."
        } finally {
            searching = false
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(reader, page) { scroll.scrollTo(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (owners.size > 1) "Shared contribution" else "Contributing profile") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(passage, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { candidate ->
                        FilterChip(selected = candidate.platformUid == selectedUid, onClick = { selectedUid = candidate.platformUid }, label = { Text(candidate.platformName) })
                    }
                }
                source?.let { Text("Original reply · ${it.platformName}" + it.modelName.takeIf(String::isNotBlank)?.let { model -> " · $model" }.orEmpty(), style = MaterialTheme.typography.titleSmall) }
                if (loaded.total > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page-- }, enabled = page > 0 && !searching) { Text("Previous page") }
                        Text("${page + 1} / ${loaded.total}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp).semantics { contentDescription = "Original reply page ${page + 1} of ${loaded.total}" })
                        TextButton(onClick = { page++ }, enabled = page + 1 < loaded.total && !searching) { Text("Next page") }
                    }
                }
                TextButton(onClick = { findRequest++ }, enabled = reader != null && !searching && !loaded.failed) { Text(if (searching) "Finding related wording…" else "Find related wording") }
                findMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                when {
                    loaded.failed -> Text("The original reply is unavailable for this saved revision.", color = MaterialTheme.colorScheme.error)
                    loaded.block == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Text(requireNotNull(loaded.block).text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false).verticalScroll(scroll))
                }
                Text("Source wording links do not certify factual accuracy.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
