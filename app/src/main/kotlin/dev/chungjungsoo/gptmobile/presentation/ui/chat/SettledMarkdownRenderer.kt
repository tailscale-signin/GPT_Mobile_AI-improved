package dev.chungjungsoo.gptmobile.presentation.ui.chat

import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Renderer syntax only: no Activity, font, density, colour, interaction owner or measured layout. */
internal class SettledMarkdownRenderer internal constructor(success: State.Success, references: Map<String, String?>) : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow<State>(success).asStateFlow()
    override val links: StateFlow<Map<String, String?>> = MutableStateFlow(references).asStateFlow()
    override suspend fun parse(): State = state.value
}

/** Bounded process-local syntax reuse. Private/streaming content never reads or writes this cache. */
internal class SettledMarkdownRendererCache(private val maximumBytes: Int = 2 * 1024 * 1024, private val maximumEntries: Int = 24, private val retentionAllowed: () -> Boolean = { true }) {
    private data class Entry(val renderer: SettledMarkdownRenderer, val charge: Int)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0
    private var generation = 0L

    init {
        require(maximumBytes > 0 && maximumEntries > 0)
    }

    fun prepare(content: String, cacheable: Boolean, checkpoint: () -> Unit = {}): SettledMarkdownRenderer? {
        checkpoint()
        val canRetain = cacheable && retentionAllowed() && content.length <= 128 * 1024
        val epoch = synchronized(this) {
            if (!retentionAllowed()) clear()
            if (canRetain) entries[content]?.let { return it.renderer }
            generation
        }
        val references = linkedMapOf<String, String?>()
        val handler = object : ReferenceLinkHandler {
            override fun store(label: String, destination: String?) {
                references[label] = destination
            }
            override fun find(label: String): String = references.entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value.orEmpty()
        }
        val parsed = parseMarkdown(content, lookupLinks = true, referenceLinkHandler = handler) as? State.Success ?: return null
        checkpoint()
        val retainedReferences = references.toMap()
        val normalized = retainedReferences.mapKeys { it.key.lowercase(Locale.ROOT) }
        val frozenHandler = object : ReferenceLinkHandler {
            override fun store(label: String, destination: String?) = Unit
            override fun find(label: String): String = normalized[label.lowercase(Locale.ROOT)].orEmpty()
        }
        val renderer = SettledMarkdownRenderer(parsed.copy(referenceLinkHandler = frozenHandler), retainedReferences)
        if (canRetain) {
            // AST nodes retain children and offsets. Charge conservatively by both
            // syntax node count and text; overlarge documents still render uncached.
            val pending = ArrayDeque<org.intellij.markdown.ast.ASTNode>()
            pending.add(parsed.node)
            var charge = content.length.toLong() * 4 + retainedReferences.entries.sumOf { (it.key.length.toLong() + (it.value?.length ?: 0)) * 4 + 64 }
            while (pending.isNotEmpty() && charge <= maximumBytes) {
                checkpoint()
                charge += 192
                pending.addAll(pending.removeLast().children)
            }
            if (charge <= maximumBytes) {
                synchronized(this) {
                    // Memory pressure/privacy clearing also fences a parse already in flight.
                    if (generation == epoch && retentionAllowed()) {
                        entries.remove(content)?.let { bytes -= it.charge }
                        entries[content] = Entry(renderer, charge.coerceAtLeast(1).toInt())
                        bytes += charge.coerceAtLeast(1).toInt()
                        while (bytes > maximumBytes || entries.size > maximumEntries) {
                            val oldest = entries.entries.iterator()
                            bytes -= oldest.next().value.charge
                            oldest.remove()
                        }
                    }
                }
            }
        }
        return renderer
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        bytes = 0
    }

    @Synchronized
    internal fun retainedBytes() = bytes

    @Synchronized
    internal fun retainedEntries() = entries.size
}

internal fun chatRenderCacheRetentionAllowed(): Boolean =
    !dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.privateSessionActive &&
        !dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents.privateSession.value

internal val settledMarkdownRendererCache = SettledMarkdownRendererCache(retentionAllowed = ::chatRenderCacheRetentionAllowed)
