package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Buffer the editorial answer until all frozen inputs pass omission checks. Never replay contributor requests. */
internal class CombinedDeliverySession(
    private val base: AgentProviderSession,
    private val sources: List<CombinedModelResponse>,
    private val maxCharacters: Int,
    private val committedPrefix: String = "",
    private val validateAccess: suspend () -> Unit,
    private val onCoverage: suspend (CombinedContentCoverage.Result, Boolean) -> Unit = { _, _ -> },
    private val repair: suspend (String, List<CombinedContentCoverage.Unit>) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = base.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val coverage = withContext(Dispatchers.Default) { CombinedContentCoverage(sources) }
        suspend fun collect(candidate: AgentProviderSession): Pair<String, ProviderEvent?> {
            val text = StringBuilder()
            var terminal: ProviderEvent? = null
            flow { emitAll(candidate.streamRound(emptyList(), emptyList())) }.catch { error ->
                if (error is CancellationException) throw error
                // Catch provider failures upstream only; collector failures must remain transparent.
                emit(ProviderEvent.Failed("Combined synthesis stream failed. Buffered text and original contributions are retained."))
            }.collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        check(text.length.toLong() + event.text.length <= maxCharacters) { "Combined answer exceeds the configured delivery allowance." }
                        text.append(event.text)
                    }
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Combined coverage repair cannot execute tools.")
                    is ProviderEvent.Failed -> terminal = event
                    ProviderEvent.Completed -> if (terminal == null) terminal = event
                    else -> emit(event)
                }
            }
            return text.toString() to terminal
        }
        emit(ProviderEvent.Notice("Combining all contributions and checking for omitted material."))
        val (draft, terminal) = collect(base)
        validateAccess()
        if (!draft.startsWith(committedPrefix)) {
            emit(ProviderEvent.Failed("Combined delivery cannot replace the already committed answer prefix. Original contributions and saved text remain available."))
            return@flow
        }
        if (terminal != ProviderEvent.Completed) {
            if (draft.isNotBlank()) emit(ProviderEvent.TextDelta(draft))
            emit(terminal as? ProviderEvent.Failed ?: ProviderEvent.Failed("Combined synthesis ended before coverage could be checked. Original contributions remain available."))
            return@flow
        }
        var text = draft
        var checked = withContext(Dispatchers.Default) { coverage.check(text) }
        onCoverage(checked, false)
        if (!checked.accepted) {
            emit(ProviderEvent.Notice("Repairing omitted material from ${checked.missing.size} of ${checked.total} source passages."))
            val repaired = withTimeoutOrNull(90_000L) {
                val attempt = try {
                    repair(text, checked.missing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    validateAccess()
                    null
                }
                attempt?.let { collect(it) }
            }
            validateAccess()
            if (repaired?.second == ProviderEvent.Completed && repaired.first.startsWith(committedPrefix)) {
                val next = withContext(Dispatchers.Default) { coverage.check(repaired.first) }
                onCoverage(next, true)
                if (next.accepted) {
                    text = repaired.first
                    checked = next
                }
            }
        }
        validateAccess()
        if (text.isNotBlank()) emit(ProviderEvent.TextDelta(text))
        if (checked.accepted && text.startsWith(committedPrefix)) {
            emit(ProviderEvent.Completed)
        } else {
            emit(ProviderEvent.Failed("COMBINED_COVERAGE_INCOMPLETE: The draft still omits or cannot account for ${checked.missing.size} of ${checked.total} frozen source passages after one repair. Original contributions remain available; this is not a fully combined answer."))
        }
    }
}
