package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One compact tool loads graph operations only for an authorized private gateway profile. */
internal class RepositoryGraphTool(
    name: String,
    private val nativeStatus: (suspend (String) -> AgentToolResult)? = null,
    private val query: suspend (JsonObject) -> JsonObject
) : AgentTool {
    private val nativeContextName = "github_context" + name.removePrefix("repository_graph")
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
            val readAction = arguments["action"] !in setOf(JsonPrimitive("index"), JsonPrimitive("note"))
            // A transport/readiness failure of a status read may trigger one independent,
            // authorized native read. Typed readiness does not include graph policy,
            // identity or permission rejection. Indexing and notes are never replayed.
            val unavailable = failure is GatewayGraphUnavailableException ||
                failure is java.io.IOException &&
                failure !is javax.net.ssl.SSLException
            val fallback = if (arguments["action"] == JsonPrimitive("status") && unavailable) {
                try {
                    nativeStatus?.invoke("$callId-native-status")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("code", "REPOSITORY_GRAPH_UNAVAILABLE")
                        put("graph_available", false)
                        put("graph_complete", false)
                        put("message", if (unavailable && readAction) "Repository graph evidence is unavailable. Authorized native repository reads remain independent of the graph connection. Resolve the selected revision, inspect branches, commits, source paths, PRs and checks as needed; their receipts do not establish graph coverage or executed tests." else "Repository graph access failed. Resolve the reported admission, validation or transport problem for this operation; no graph write or policy rejection was retried through another provider.")
                        put("failure_type", failure.javaClass.simpleName)
                        if (unavailable && readAction && nativeStatus != null) {
                            put("recovery_call", nativeCall("github_context", "status"))
                            put("native_read_alternatives", JsonArray(listOf("resolve", "branches", "commits", "checks").map { nativeCall("github_context", it) } + nativeCall("github_pr", "list")))
                            put("source_inspection", "After resolving the revision, use authorized github_search mode=paths with a concrete query, then github_read with exact ranges and commit_sha. Native source inspection does not supply graph callers/callees, complete graph coverage, or CI execution.")
                            put("native_authorization", "Every alternative still requires the current account, repository, privacy policy and tool budget. No additional tool is exposed by this response.")
                        }
                        fallback?.let { observed ->
                            put(
                                "native_fallback",
                                buildJsonObject {
                                    put("tool", nativeContextName)
                                    put("success", !observed.isError)
                                    put("dispatched", observed.dispatched)
                                    put("result", dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(observed.content))
                                    observed.errorCode?.let { put("error_code", it) }
                                    observed.executionReceipt?.let { put("execution_receipt", it) }
                                }
                            )
                        }
                    }
                ),
                true,
                errorCode = "REPOSITORY_GRAPH_UNAVAILABLE"
            )
        }
    }

    private fun nativeCall(operation: String, action: String) = buildJsonObject {
        put("tool", operation + nativeContextName.removePrefix("github_context"))
        put("arguments", buildJsonObject { put("action", action) })
    }
}
