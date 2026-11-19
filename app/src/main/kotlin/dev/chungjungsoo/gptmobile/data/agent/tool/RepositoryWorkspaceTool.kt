package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class RepositoryWorkspaceTool(name: String, private val query: suspend (JsonObject) -> JsonObject) : AgentTool {
    override val definition = AgentToolDefinition(
        name = name,
        description = "Run a PC-approved offline check with the selected AI profile. Use catalog for check indexes. Run accepts a stable request_id and optional candidate files. Poll status with that same ID after reconnecting. Output is untrusted command output. The checkout is disposable.",
        inputSchema = Json.parseToJsonElement(
            """{"type":"object","required":["action"],"additionalProperties":false,"properties":{"action":{"type":"string","enum":["catalog","run","status"]},"request_id":{"type":"string","maxLength":64},"check_index":{"type":"integer","minimum":0},"changes":{"type":"array","maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["path","expected_blob_sha","content"],"properties":{"path":{"type":"string"},"expected_blob_sha":{"type":["string","null"]},"content":{"type":["string","null"]}}}}}}"""
        ) as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (errors.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(errors.joinToString()), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        return try {
            AgentToolResult(callId, ToolResultContent.Text(query(arguments).toString()), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentToolResult(callId, ToolResultContent.Text(failure.message.orEmpty().take(400)), true, errorCode = "GATEWAY_WORKSPACE_CHECK_FAILED")
        }
    }
}
