package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ApprovedReadOnlyAgentTool
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata
import dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics
import dev.chungjungsoo.gptmobile.data.agent.ToolReplaySafety
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Measures each call independently, including local-runtime and shared-result delivery. */
class MeasuredAgentTool(
    private val delegate: AgentTool,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onMeasured: (AgentToolResult) -> Unit = {},
    override val approvedReadOnly: Boolean = false
) : AgentTool,
    OwnedAgentTool,
    ApprovedReadOnlyAgentTool {
    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = MeasuredAgentTool(delegate.withChildBudget(bind), nanoTime, onMeasured, approvedReadOnly)
    override val definition = delegate.definition
    override val managesExecutionBudget = delegate.managesExecutionBudget
    override val coordinatesChildWork = delegate.coordinatesChildWork
    override val executionOwner: AgentToolExecutionOwner =
        (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence]?.dispatched()
        val replaySafe = ToolReplaySafety.isReplayableRead(definition.name, arguments, this)
        val started = nanoTime()
        val result = try {
            delegate.execute(callId, arguments)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (error is dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected && ToolFailureMetadata.authorityCode(error) == null) {
                AgentToolResult(callId, ToolResultContent.Text(error.message.orEmpty()), isError = true, errorCode = error.code, dispatched = false)
            } else {
                // The delegate was invoked. An authority change cannot establish
                // that a mutation had no effect, even when carried by a refusal type.
                val cause = ToolFailureMetadata.authorityCode(error) ?: ToolFailureMetadata.classify(error)
                val failure = AgentToolResult(callId, ToolResultContent.Text(error.message ?: "Tool execution failed."), isError = true, errorCode = if (replaySafe) cause else "DISPATCH_OUTCOME_UNKNOWN")
                ToolFailureMetadata.receipt(
                    ToolFailureMetadata.withAuthorityFailure(failure, error, "tool_execution") ?: failure,
                    definition.name,
                    diagnosticCode = cause,
                    replaySafe = replaySafe
                )
            }
        }
        val normalized = ToolFailureMetadata.receipt(result, definition.name, replaySafe = replaySafe)
        val measured = normalized.copy(
            measurement = ToolPayloadMetrics.measure(
                arguments = arguments.toString(),
                content = normalized.content,
                durationMs = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0),
                shared = normalized.sharedResult
            )
        )
        // Observational telemetry must never make an otherwise successful tool fail.
        runCatching { onMeasured(measured) }
        return measured
    }
}
