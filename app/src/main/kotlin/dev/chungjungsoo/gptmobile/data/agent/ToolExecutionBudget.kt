package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** One budget per user turn, shared by every bound tool, including the native engine bridge. */
class ToolExecutionBudget(
    private val limits: AgentRunLimits,
    private val failureMessage: (Exception) -> String = { "Tool execution failed. Check the connection diagnostics before repeating an action." }
) {
    private val calls = AtomicInteger()
    private val completed = AtomicInteger()
    private val remainingBytes = AtomicInteger(limits.maxToolOutputBytes)
    private val permits = Semaphore(limits.maxConcurrentTools.coerceAtLeast(1))
    private val executionLimit = ToolBudgetPolicy.executionLimit(limits)

    fun bind(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true }
    ): AgentTool = object : AgentTool {
        override val definition = tool.definition
        override val managesExecutionBudget = true

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            fun failure(message: String) = AgentToolResult(callId, ToolResultContent.Text(message), true)
            if (!tryAcquireCall()) {
                val message = toolCallBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-call limit blocked execution · tool=${tool.definition.name} · call=$callId · used=${calls.get()} · executableLimit=$executionLimit · configured=${limits.maxToolCalls} · reserved=${limits.finalResponseToolCallReserve}",
                    "W"
                )
                return withBudgetState(
                    failure(message).copy(
                        traceContent = ToolResultContent.Text(message),
                        toolCallBudgetExhausted = true
                    )
                )
            }
            if (remainingBytes.get() <= 0) {
                val message = outputBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-result byte budget blocked execution · tool=${tool.definition.name} · call=$callId · configuredBytes=${limits.maxToolOutputBytes}",
                    "W"
                )
                return withBudgetState(
                    failure(message).copy(
                        traceContent = ToolResultContent.Text(message),
                        outputBudgetExhausted = true
                    )
                )
            }
            if (!authorize(callId, arguments)) return bounded(failure("Tool permission was denied or this action was already dispatched."))
            var success = false
            try {
                val result = if (tool.managesExecutionBudget) {
                    // Orchestrators own their deadline; their children use this same budget.
                    // Holding a permit here would deadlock nested calls at concurrency = 1.
                    tool.execute(callId, arguments)
                } else {
                    permits.withPermit {
                        withTimeoutOrNull(limits.toolTimeoutMillis) { tool.execute(callId, arguments) }
                            ?: failure("Tool timed out. Its outcome may be unknown; check before repeating a write.")
                    }
                }
                val boundedResult = bounded(
                    result,
                    preserveSuccessfulHandoff = tool.definition.name == DELEGATION_TOOL_NAME
                )
                success = !boundedResult.isError
                return boundedResult
            } catch (cancellation: CancellationException) {
                AppLogRecorder.record(
                    "Tool",
                    "Canceled by parent run · tool=${tool.definition.name} · call=$callId",
                    "W"
                )
                throw cancellation
            } catch (error: Exception) {
                val boundedResult = bounded(failure(failureMessage(error)))
                success = !boundedResult.isError
                return boundedResult
            } finally {
                withContext(NonCancellable) { onFinished(callId, success) }
            }
        }
    }

    private fun tryAcquireCall(): Boolean {
        if (executionLimit == Int.MAX_VALUE) {
            calls.incrementAndGet()
            return true
        }
        while (true) {
            val current = calls.get()
            if (current >= executionLimit.coerceAtLeast(0)) return false
            if (calls.compareAndSet(current, current + 1)) return true
        }
    }

    private fun bounded(
        result: AgentToolResult,
        preserveSuccessfulHandoff: Boolean = false
    ): AgentToolResult {
        val text = when (val value = result.content) {
            is ToolResultContent.Text -> value.text
            is ToolResultContent.Json -> value.value.toString()
            is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { it.uri }
        }
        val checkpoint = if (completed.incrementAndGet() % ToolProgressTracker.INTERVAL == 0) {
            "\n\n" + ToolProgressTracker.SUMMARY_INSTRUCTION
        } else {
            ""
        }
        val size = (text + checkpoint).toByteArray(Charsets.UTF_8).size
        val sharedAvailable = remainingBytes.getAndUpdate { (it.toLong() - size).coerceAtLeast(0).toInt() }.coerceAtLeast(0)
        val handoffReserve = if (
            preserveSuccessfulHandoff &&
            !result.isError &&
            size > sharedAvailable
        ) {
            minOf(DELEGATION_HANDOFF_RESERVE_BYTES, size - sharedAvailable)
        } else {
            0
        }
        val available = (sharedAvailable.toLong() + handoffReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        // Delegation is an orchestrator: its nested search/read calls may legitimately
        // consume the shared raw-result budget before the compact final handoff exists.
        // Preserve that compact handoff in a small emergency reserve rather than
        // replacing successful research with a misleading budget error.
        if (handoffReserve > 0) {
            AppLogRecorder.record(
                "ToolBudget",
                "Preserving delegated handoff after nested result budget use · call=${result.callId} · resultBytes=$size · sharedAvailable=$sharedAvailable · reserveBytes=$handoffReserve",
                "W"
            )
        }

        // Control messages are bounded separately: providers reject empty error results,
        // and a zero payload allowance must still let the model finish the turn.
        if (available == 0) {
            val message = outputBudgetMessage()
            return withBudgetState(
                result.copy(
                    content = ToolResultContent.Text(message),
                    traceContent = ToolResultContent.Text(message),
                    isError = true,
                    outputBudgetExhausted = true,
                    toolCallBudgetExhausted = result.toolCallBudgetExhausted || callBudgetIsExhausted()
                )
            )
        }
        val checkpointBytes = checkpoint.toByteArray(Charsets.UTF_8).size
        val bounded = if (checkpointBytes <= available) {
            truncateUtf8(text, available - checkpointBytes) + checkpoint
        } else {
            truncateUtf8(text, available)
        }
        val safeText = bounded.ifBlank {
            if (size > available) {
                outputBudgetMessage()
            } else if (result.isError) {
                "Tool failed without error details."
            } else {
                "Tool returned no content."
            }
        }
        val changed = size > available || checkpoint.isNotEmpty() || bounded.isBlank()
        val trace = if (size > available) {
            // Respect tools that deliberately supply a redacted trace; otherwise show
            // the useful search/page excerpt as well as the truncation notice.
            val excerpt = result.traceContent?.let { value ->
                when (value) {
                    is ToolResultContent.Text -> value.text
                    is ToolResultContent.Json -> value.value.toString()
                    is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { it.uri }
                }
            } ?: safeText
            ToolResultContent.Text(truncateUtf8(excerpt, available) + "\n\n[Result truncated to the run's output budget.]")
        } else {
            result.traceContent
        }
        return withBudgetState(
            result.copy(
                content = if (changed) ToolResultContent.Text(safeText) else result.content,
                traceContent = trace,
                outputBudgetExhausted = result.outputBudgetExhausted || size >= sharedAvailable,
                toolCallBudgetExhausted = result.toolCallBudgetExhausted || callBudgetIsExhausted()
            )
        )
    }

    private fun withBudgetState(result: AgentToolResult): AgentToolResult {
        val configuredCalls = limits.maxToolCalls.takeUnless { it == Int.MAX_VALUE }
        val callLimit = executionLimit.takeUnless { it == Int.MAX_VALUE }
        val resultByteLimit = limits.maxToolOutputBytes.takeUnless { it == Int.MAX_VALUE }
        val resultBytesUsed = resultByteLimit?.let { limit ->
            (limit.toLong() - remainingBytes.get().toLong()).coerceIn(0L, limit.toLong()).toInt()
        }
        return result.copy(
            toolCallBudgetUsed = calls.get(),
            toolCallBudgetLimit = callLimit,
            toolCallBudgetConfigured = configuredCalls,
            toolCallBudgetReserved = configuredCalls?.let { limits.finalResponseToolCallReserve.coerceAtLeast(0) },
            toolResultBudgetUsedBytes = resultBytesUsed,
            toolResultBudgetLimitBytes = resultByteLimit
        )
    }

    private fun callBudgetIsExhausted(): Boolean =
        executionLimit != Int.MAX_VALUE && calls.get() >= executionLimit

    private fun toolCallBudgetMessage(): String {
        val reserve = limits.finalResponseToolCallReserve.coerceAtLeast(0)
        val configured = limits.maxToolCalls.coerceAtLeast(0)
        return "Tool-call limit reached: ${calls.get()}/$executionLimit executable calls used " +
            "($configured configured, $reserve reserved for finalization). " +
            "Use the successful results already available. Increase Maximum Tool Calls on the active AI profile if more research is required."
    }

    private fun outputBudgetMessage(): String {
        val configured = limits.maxToolOutputBytes.coerceAtLeast(0)
        return "Tool-result byte budget exhausted: $configured/$configured bytes used. " +
            "Use the successful results already available; completed delegated research should still be summarized."
    }

    private companion object {
        const val DELEGATION_TOOL_NAME = "delegate_to_model"
        const val DELEGATION_HANDOFF_RESERVE_BYTES = 64 * 1024
    }
}

internal const val OUTPUT_BUDGET_EXHAUSTED = "Tool-result byte budget exhausted. Answer using the results already available; do not call more tools."

internal fun truncateUtf8(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var end = 0
    var used = 0
    while (end < text.length) {
        val point = text.codePointAt(end)
        val bytes = when {
            point < 0x80 -> 1
            point < 0x800 -> 2
            point < 0x10000 -> 3
            else -> 4
        }
        if (used + bytes > maxBytes) break
        used += bytes
        end += Character.charCount(point)
    }
    return text.substring(0, end)
}
