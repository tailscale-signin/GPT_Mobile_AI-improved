package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit settings probe; never includes chat, memory, credentials or connected tools. */
internal object AssistTestFixture {
    fun tool(): AgentTool = object : AgentTool {
        override val definition = AgentToolDefinition("synthetic_assist_probe", "Synthetic local selection fixture", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put(
                        "results",
                        JsonArray(
                            (0..11).map { index ->
                                buildJsonObject {
                                    put("id", index)
                                    put("title", if (index % 2 == 0) "Reusable water bottle" else "Notebook")
                                    put("source", "https://example.invalid/fixture/$index")
                                    put("description", (if (index % 2 == 0) "A synthetic bottle comparison; 500 mL capacity. Some users report leaks. " else "A synthetic notebook comparison; 80 pages. Unrelated to bottle capacity. ").repeat(7))
                                }
                            }
                        )
                    )
                }
            ),
            false
        )
    }
}
