package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistCoordinatorTest {
    private val records = JsonArray(
        (0..8).map { id ->
            buildJsonObject {
                put("id", id)
                put("mass", "12 kg")
                put("approval", "NOT approved")
                put("source", "https://example.org/$id")
                put("padding", "context ".repeat(90))
            }
        }
    )
    private val payload = buildJsonObject {
        put("results", records)
        put("notice", "Conflicting public observations; dates unconfirmed")
    }
    private fun tool() = object : AgentTool {
        override val definition = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Json(payload), false)
    }

    @Test
    fun selectedRecordsAreCopiedExactlyWithQualifications() = runBlocking {
        val assist = AssistCoordinator("Find evidence", { true }, { "[0,2]" }, {})
        val result = assist.bind(tool()).execute("call", buildJsonObject {})
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonArray(listOf(records[0], records[2])), data["results"])
        assertEquals(payload["notice"], data["notice"])
        assertEquals(JsonPrimitive(true), data["assistSelection"])
        assertFalse(result.isError)
    }

    @Test
    fun invalidModelIndicesCannotInventEvidence() = runBlocking {
        val assist = AssistCoordinator("Find evidence", { true }, { "[999]" }, {})
        val result = assist.bind(tool()).execute("call", buildJsonObject {})
        assertEquals(payload, (result.content as ToolResultContent.Json).value)
    }

    @Test
    fun textProjectionRejectsFabricatedQuotesAndPreservesOriginal() = runBlocking {
        val source = "Mass: 12 kg; NOT approved. " + "source evidence ".repeat(300)
        val invalid = AssistCoordinator("Find mass", { true }, { """{"excerpts":[{"start":0,"end":11,"quote":"Mass: 99 kg"}]}""" }, {}, policy = AssistPolicy(extractEvidence = true))
        assertEquals(null, invalid.prepareText(source))
        val valid = AssistCoordinator("Find mass", { true }, { """{"excerpts":[{"start":0,"end":11,"quote":"Mass: 12 kg"}]}""" }, {}, policy = AssistPolicy(extractEvidence = true))
        val projection = kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(valid.prepareText(source))) as JsonObject
        val result = valid.evidenceReader().execute(
            "expand",
            buildJsonObject {
                put("handle", projection.getValue("sourceHandle"))
                put("revision", projection.getValue("sourceRevision"))
                put("start", 0)
                put("end", 25)
            }
        )
        assertEquals(source.take(25), (result.content as ToolResultContent.Text).text)
    }

    @Test
    fun coldOrBusyWorkerIsBypassed() = runBlocking {
        var workerCalls = 0
        val assist = AssistCoordinator("Find evidence", { false }, {
            workerCalls++
            "[0]"
        }, {})
        assist.bind(tool()).execute("call", buildJsonObject {})
        assertEquals(0, workerCalls)
    }
}
