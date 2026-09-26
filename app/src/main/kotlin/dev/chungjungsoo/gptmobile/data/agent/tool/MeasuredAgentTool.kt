package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Measures each call independently, including local-runtime and shared-result delivery. */
class MeasuredAgentTool(
    private val delegate: AgentTool,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onMeasured: (AgentToolResult) -> Unit = {}
) : AgentTool {
    override val definition = delegate.definition
    override val managesExecutionBudget = delegate.managesExecutionBudget

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val started = nanoTime()
        val result = try {
            delegate.execute(callId, arguments)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AgentToolResult(callId, ToolResultContent.Text(error.message ?: "Tool execution failed."), isError = true)
        }
        val measured = result.copy(
            measurement = ToolPayloadMetrics.measure(
                arguments = arguments.toString(),
                content = result.content,
                durationMs = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0),
                shared = result.sharedResult
            )
        )
        // Observational telemetry must never make an otherwise successful tool fail.
        runCatching { onMeasured(measured) }
        return measured
    }
}
