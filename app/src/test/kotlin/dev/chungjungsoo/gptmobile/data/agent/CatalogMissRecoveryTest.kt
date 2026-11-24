package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogMissRecoveryTest {
    private val revision = "a".repeat(64)
    private val catalog = ProgressiveToolCatalog(emptyList(), "research").definition
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}""") as JsonObject
    private val read = AgentToolDefinition("read_url", "Read a URL", schema, setOf(AgentToolCapability.PAGE_READER))
    private val args = buildJsonObject { put("url", "https://example.org/article") }

    private fun miss(id: String, name: String, rev: String = revision, arguments: JsonObject = args, suggested: String? = null): AgentToolExchange {
        val call = ProviderEvent.ToolCall(id, name, arguments)
        val receipt = buildJsonObject {
            put("schema", "gptmobile.round-tool-catalog.v1")
            put("call_id", id)
            put("reason_code", "UNKNOWN_TOOL")
            put("discovery_available", true)
            put("catalog_revision_hash", rev)
            suggested?.let { put("suggested_tool", it) }
        }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Text("unavailable"), true, dispatched = false, errorCode = "TOOL_NOT_AVAILABLE", executionReceipt = receipt)))
    }

    private fun response(lookup: ProviderEvent.ToolCall, vararg names: String): AgentToolExchange {
        val body = buildJsonObject { put("available_next_round", JsonArray(names.map { name -> buildJsonObject { put("name", name) } })) }
        return AgentToolExchange(listOf(lookup), listOf(AgentToolResult(lookup.callId, ToolResultContent.Json(body), false, dispatched = false)))
    }

    @Test fun interleavedMissesEachGetOneLocalLookupUntilTheCatalogChanges() {
        val history = mutableListOf(miss("1", "missing_a"), miss("2", "missing_b"), miss("3", "missing_a"))
        val tools = listOf(catalog, read)
        val first = requireNotNull(CatalogMissRecovery.next(history, tools, emptySet()))
        assertEquals(JsonPrimitive("missing_a"), first.arguments["query"])
        assertEquals(JsonPrimitive(true), first.arguments["catalog_only"])
        history += response(first)
        val second = requireNotNull(CatalogMissRecovery.next(history, tools, setOf(first.callId)))
        assertEquals(JsonPrimitive("missing_b"), second.arguments["query"])
        history += response(second)
        assertNull(CatalogMissRecovery.next(history, tools, setOf(first.callId, second.callId)))
        history += miss("4", "missing_a", rev = "b".repeat(64))
        val refreshed = requireNotNull(CatalogMissRecovery.next(history, tools, setOf(first.callId, second.callId)))
        assertNotEquals(first.callId, refreshed.callId)
    }

    @Test fun onlyAnAdmittedReturnedReadWithItsOriginalValidArgumentsIsRetriedOnce() {
        val tools = listOf(catalog, read)
        val failure = miss("1", "old_reader", suggested = read.name)
        val lookup = requireNotNull(CatalogMissRecovery.next(listOf(failure), tools, emptySet()))
        assertNull(CatalogMissRecovery.next(listOf(failure, response(lookup, "other")), tools, emptySet()))
        assertNull(CatalogMissRecovery.next(listOf(failure, response(lookup, read.name)), listOf(catalog), emptySet()))
        val history = listOf(failure, response(lookup, read.name))
        val action = requireNotNull(CatalogMissRecovery.next(history, tools, emptySet()))
        assertEquals(read.name, action.name)
        assertEquals(args, action.arguments)
        assertNull(CatalogMissRecovery.next(history, tools, setOf(action.callId)))
        val invalid = miss("1", "old_reader", arguments = JsonObject(emptyMap()), suggested = read.name)
        assertNull(CatalogMissRecovery.next(listOf(invalid, response(lookup, read.name)), tools, emptySet()))
        val write = read.copy(name = "create_issue", capabilities = setOf(AgentToolCapability.GITHUB))
        val writeMiss = miss("2", write.name)
        val findWrite = requireNotNull(CatalogMissRecovery.next(listOf(writeMiss), listOf(catalog, write), emptySet()))
        assertNull(CatalogMissRecovery.next(listOf(writeMiss, response(findWrite, write.name)), listOf(catalog, write), emptySet()))
    }
}
