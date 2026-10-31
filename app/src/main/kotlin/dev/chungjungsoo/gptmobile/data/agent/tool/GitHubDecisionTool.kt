package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecision
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionOption
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Local interactive input; it cannot authorize or execute a repository mutation. */
internal class GitHubDecisionTool(
    private val decisions: GitHubChatDecisions,
    private val chatId: Int,
    private val sourceMessageId: Int,
    private val profileUid: String,
    private val workspace: GitHubConversationContext,
    private val persistent: Boolean,
    private val allowed: suspend () -> Boolean,
    private val offered: suspend (GitHubChatDecision) -> Unit = {}
) : AgentTool {
    override val managesExecutionBudget = true
    override val definition = AgentToolDefinition(
        NAME,
        "Ask the user a consequential feature/design question with 2-4 button choices. Waits for their answer; never selects a default or approves GitHub writes. Ask early about scope, UI, behavior and architecture, and again at meaningful design checkpoints. Reuse answers; do not ask about routine fixes or settled choices.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    fun text(max: Int) = buildJsonObject {
                        put("type", "string")
                        put("maxLength", max)
                    }
                    put("question", text(400))
                    put("context", text(1200))
                    put(
                        "options",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", 4)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("label", text(80))
                                            put("detail", text(240))
                                        }
                                    )
                                    put("required", JsonArray(listOf(JsonPrimitive("label"))))
                                    put("additionalProperties", false)
                                }
                            )
                        }
                    )
                    put(
                        "recommended",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 3)
                            put("description", "Optional zero-based option index; only a recommendation.")
                        }
                    )
                }
            )
            put("required", JsonArray(listOf("question", "options").map(::JsonPrimitive)))
            put("additionalProperties", false)
        },
        capabilities = setOf(AgentToolCapability.USER_DECISION)
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        check(allowed()) { "The GitHub conversation or profile changed; this decision is no longer active." }
        val scope = GitHubChatDecisions.workspaceKey(workspace)
        val question = arguments.getValue("question").jsonPrimitive.content.trim()
        val context = arguments["context"]?.jsonPrimitive?.content.orEmpty().trim()
        val options = (arguments.getValue("options") as JsonArray).map { item ->
            GitHubDecisionOption(item.jsonObject.getValue("label").jsonPrimitive.content.trim(), item.jsonObject["detail"]?.jsonPrimitive?.content.orEmpty().trim())
        }
        val proposal = GitHubChatDecision("", chatId, sourceMessageId, profileUid, scope, question, context, options, arguments["recommended"]?.jsonPrimitive?.intOrNull, persistent)
        val id = GitHubRepositoryStateStore.digest(Json.encodeToString(GitHubChatDecision.serializer(), proposal.copy(persistent = false)))
        val answer = decisions.request(proposal.copy(id = id), offered)
        check(allowed()) { "The GitHub conversation or profile changed while waiting. The choice is saved; no new action is authorized." }
        decisions.delivered(id)
        return AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("question", question)
                    put("user_answer", requireNotNull(answer.answer))
                    put("cancelled", answer.cancelled)
                    put("instruction", if (answer.cancelled) "Stop tool work and summarize retained results." else "Apply this user choice to the current task. Continue independent work and ask the next consequential design question when evidence is ready. This answer is not write approval.")
                }
            ),
            isError = answer.cancelled,
            dispatched = false,
            sharedResult = true,
            errorCode = if (answer.cancelled) "GITHUB_USER_STOP" else null
        )
    }

    companion object {
        const val NAME = "github_decide"
    }
}
