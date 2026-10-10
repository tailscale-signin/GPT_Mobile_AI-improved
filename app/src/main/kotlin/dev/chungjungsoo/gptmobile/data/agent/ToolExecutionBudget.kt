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
    private val evidenceBytesPerResult: Int? = null,
    private val failureMessage: (Exception) -> String = { "Tool execution failed. Check the connection diagnostics before repeating an action." }
) {
    private val unknownOutcomes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val calls = AtomicInteger()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val completed = AtomicInteger()
    private val remainingBytes = AtomicInteger(limits.maxToolOutputBytes)
    private val permits = Semaphore(limits.maxConcurrentTools.coerceAtLeast(1))
    private val executionLimit = ToolBudgetPolicy.executionLimit(limits)

    fun canExecute(): Boolean = remainingBytes.get() > 0 && !callBudgetIsExhausted()
    fun remainingOutputBytes(): Int = remainingBytes.get().coerceAtLeast(0)

    fun bind(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true }
    ): AgentTool = object : PreparableAgentTool, OwnedAgentTool {
        override val definition = tool.definition
        override val managesExecutionBudget = true
        override val executionOwner = (tool as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            fun failure(message: String) = ToolResultEnvelope.error(callId, "BUDGET_EXHAUSTED", message)
            val signature = tool.definition.name + ":" + arguments.toString().hashCode()
            if (signature in unknownOutcomes) return { withBudgetState(ToolResultEnvelope.error(callId, "RECONCILIATION_REQUIRED", "Previous dispatch outcome is unknown. Reconcile it before repeating this action.")) }
            if ((failures[signature]?.get() ?: 0) >= 2) return { withBudgetState(ToolResultEnvelope.error(callId, "REPEATED_FAILURE", "An unchanged call failed twice. Stop repeating it; use retained evidence or correct the request.")) }
            val invalid = ToolArgumentValidator.errors(arguments, tool.definition.inputSchema)
            if (invalid.isNotEmpty()) return { withBudgetState(ToolResultEnvelope.error(callId, "INVALID_ARGUMENTS", invalid.joinToString("; "))) }
            if (!tryAcquireCall()) {
                val message = toolCallBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-call limit blocked execution · tool=${tool.definition.name} · call=$callId · used=${calls.get()} · executableLimit=$executionLimit · configured=${limits.maxToolCalls} · reserved=${limits.finalResponseToolCallReserve}",
                    "W"
                )
                return {
                    withBudgetState(
                        failure(message).copy(
                            traceContent = ToolResultContent.Text(message),
                            toolCallBudgetExhausted = true
                        )
                    )
                }
            }
            if (remainingBytes.get() <= 0) {
                val message = outputBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-result byte budget blocked execution · tool=${tool.definition.name} · call=$callId · configuredBytes=${limits.maxToolOutputBytes}",
                    "W"
                )
                return {
                    withBudgetState(
                        failure(message).copy(
                            traceContent = ToolResultContent.Text(message),
                            outputBudgetExhausted = true
                        )
                    )
                }
            }
            if (!authorize(callId, arguments)) return { bounded(ToolResultEnvelope.error(callId, "AUTH_REQUIRED", "Tool permission was denied or this action was already dispatched.")) }
            val dispatched = java.util.concurrent.atomic.AtomicBoolean()
            return execution@{
                if (!dispatched.compareAndSet(false, true)) return@execution withBudgetState(failure("This prepared tool call was already dispatched."))
                var success = false
                try {
                    val result = if (tool.managesExecutionBudget) {
                        // Orchestrators own their deadline; their children use this same budget.
                        // Holding a permit here would deadlock nested calls at concurrency = 1.
                        tool.execute(callId, arguments)
                    } else {
                        permits.withPermit {
                            if (!canExecute() && remainingBytes.get() <= 0) return@withPermit failure(outputBudgetMessage())
                            withContext(ToolOutputAllowance(minOf(16 * 1024, remainingOutputBytes() / 4))) {
                                withTimeoutOrNull(limits.toolTimeoutMillis) { tool.execute(callId, arguments) }
                            }
                                ?: ToolResultEnvelope.error(callId, "TIMEOUT_OUTCOME_UNKNOWN", "Tool timed out after dispatch. Reconcile the outcome before repeating an action.", dispatched = true)
                        }
                    }
                    val boundedResult = bounded(
                        result,
                        preserveSuccessfulHandoff = tool.definition.name == DELEGATION_TOOL_NAME
                    )
                    val normalizedResult = if (
                        tool.definition.name == DELEGATION_TOOL_NAME &&
                        boundedResult.isError &&
                        boundedResult.hasSuccessfulDelegationMarker()
                    ) {
                        AppLogRecorder.record(
                            "Delegation",
                            "Corrected contradictory execution-budget error after successful delegated handoff · call=$callId",
                            "W"
                        )
                        boundedResult.copy(isError = false)
                    } else {
                        boundedResult
                    }
                    if (normalizedResult.errorCode == "TIMEOUT_OUTCOME_UNKNOWN") unknownOutcomes += signature
                    if (normalizedResult.isError && normalizedResult.errorCode !in setOf("AUTH_REQUIRED", "BUDGET_EXHAUSTED")) failures.computeIfAbsent(signature) { AtomicInteger() }.incrementAndGet()
                    success = !normalizedResult.isError
                    return@execution normalizedResult
                } catch (cancellation: CancellationException) {
                    AppLogRecorder.record(
                        "Tool",
                        "Canceled by parent run · tool=${tool.definition.name} · call=$callId",
                        "W"
                    )
                    throw cancellation
                } catch (error: Exception) {
                    val boundedResult = bounded(ToolResultEnvelope.error(callId, "PROVIDER_UNAVAILABLE", failureMessage(error), dispatched = true))
                    success = !boundedResult.isError
                    return@execution boundedResult
                } finally {
                    withContext(NonCancellable) { onFinished(callId, success) }
                }
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
        // Debit the admitted excerpt, rather than allowing a single raw JSON/page
        // payload to consume the entire turn's research budget. Keep full results
        // separately for UI and recovery, as before.
        val resultAllowance = if (evidenceBytesPerResult != null) {
            limits.maxToolOutputBytes
        } else if (preserveSuccessfulHandoff) {
            Int.MAX_VALUE
        } else {
            minOf(16 * 1024, limits.maxToolOutputBytes)
        }
        val chargedBytes = minOf(size, resultAllowance)
        val sharedAvailable = remainingBytes.getAndUpdate { (it.toLong() - chargedBytes).coerceAtLeast(0).toInt() }.coerceAtLeast(0)
        val handoffReserve = if (
            preserveSuccessfulHandoff &&
            !result.isError &&
            size > sharedAvailable
        ) {
            minOf(DELEGATION_HANDOFF_RESERVE_BYTES, size - sharedAvailable)
        } else {
            0
        }
        val payloadAvailable = (minOf(sharedAvailable, resultAllowance).toLong() + handoffReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val available = minOf(payloadAvailable, evidenceBytesPerResult ?: Int.MAX_VALUE)

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
                    retainedContent = result.retainedContent ?: result.content,
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
                content = if (changed && bounded.isBlank()) {
                    ToolResultContent.Text(safeText)
                } else if (changed) {
                    ToolResultEnvelope.compact(result.content, available)
                } else {
                    result.content
                },
                retainedContent = result.retainedContent ?: result.content.takeIf { changed },
                traceContent = trace,
                outputBudgetExhausted = result.outputBudgetExhausted || remainingBytes.get() <= 0,
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

    private fun AgentToolResult.hasSuccessfulDelegationMarker(): Boolean =
        (content as? ToolResultContent.Text)?.text?.trimStart()?.let { text ->
            text.startsWith("<!-- delegation:local -->") || text.startsWith("<!-- delegation:remote -->")
        } == true
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

/** Reaches page readers through measured/authorized wrappers without altering tool arguments. */
internal class ToolOutputAllowance(val bytes: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolOutputAllowance>
}
