package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit opt-in local planning; proposals confer neither evidence nor search permission. */
internal class AssistTurnPlanningTool(private val goal: String) : AgentTool {
    override val definition = AgentToolDefinition(
        "local_assist_plan",
        "Ask the approved local helper for up to three query proposals for the current user request. No external search is executed; proposals are unverified. Optional assistance may be skipped if unavailable or slow. Call with no arguments.",
        JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()), "additionalProperties" to JsonPrimitive(false)))
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (arguments.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text("Invalid arguments: local_assist_plan accepts no arguments"), true)
        return AgentToolResult(
            callId,
            ToolResultContent.Json(
                JsonObject(
                    mapOf(
                        "currentUserGoal" to JsonPrimitive(goal),
                        "limitation" to JsonPrimitive("Query proposals only. No source retrieval or factual verification has occurred; existing search permissions still apply.")
                    )
                )
            ),
            false
        )
    }
}
