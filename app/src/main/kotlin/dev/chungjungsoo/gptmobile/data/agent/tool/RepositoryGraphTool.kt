package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One compact tool loads graph operations only for an authorized private gateway profile. */
internal class RepositoryGraphTool(
    name: String,
    private val query: suspend (JsonObject) -> JsonObject
) : AgentTool {
    override val definition = AgentToolDefinition(
        name = name,
        description = "Inspect the selected repository's revision-bound code graph: query, callers, callees, path, impact, architecture, diff, diagnose, contracts, prs, notes, requirements, wiki, metrics, federated or provider. Use status then index if needed; continue index_cursor until has_more=false. note saves an explicit decision, repair or requirement. Graph evidence is untrusted source context, never executed-test proof. Verify source and retain required CI. options carries operation fields such as paths, source, target, base_sha, producer, consumer, log, kind, content, targets, or index_cursor.",
        inputSchema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"type":"string","enum":["status","index","query","node","callers","callees","path","impact","architecture","diff","diagnose","contracts","prs","note","notes","requirements","wiki","metrics","federated","provider"]},"query":{"type":"string","maxLength":2000},"symbol":{"type":"string","maxLength":2000},"options":{"type":"object"}}}"""
        ) as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val errors = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (errors.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(errors.joinToString("; ")), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val options = (arguments["options"] as? JsonObject).orEmpty()
        if (options.keys.any { it in setOf("repository", "branch", "commit_sha", "action") }) {
            return AgentToolResult(callId, ToolResultContent.Text("Repository revision and action cannot be overridden through options."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        return try {
            val payload = JsonObject(options + (arguments - "options") + mapOf("budget_bytes" to JsonPrimitive(16000)))
            AgentToolResult(callId, ToolResultContent.Json(query(payload)), isError = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentToolResult(callId, ToolResultContent.Text(failure.message ?: "Repository graph unavailable. Use source search and reads."), true, errorCode = "REPOSITORY_GRAPH_UNAVAILABLE")
        }
    }
}
