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
        val data = result.assistMetadata.single()
        assertEquals(payload, (result.content as ToolResultContent.Json).value)
        assertEquals(JsonArray(listOf(records[0], records[2])), data["assistSelectedRecords"])
        assertEquals(payload["notice"], ((result.content as ToolResultContent.Json).value as JsonObject)["notice"])
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
    fun coldOrBusyWorkerIsBypassed() = runBlocking {
        var workerCalls = 0
        val assist = AssistCoordinator("Find evidence", { false }, {
            workerCalls++
            "[0]"
        }, {})
        assist.bind(tool()).execute("call", buildJsonObject {})
        assertEquals(0, workerCalls)
    }

    @Test fun combinedProfilesShareOnlySameAuthorityAndCurrentTurnResults() = runBlocking {
        var generated = 0
        val turn = java.util.UUID.randomUUID().toString()
        fun coordinator() = AssistCoordinator("same goal", { true }, {
            generated++
            "[0,2]"
        }, {}, turnId = turn)
        val first = coordinator().bind(tool(), "account-a").execute("first", buildJsonObject {})
        val second = coordinator().bind(tool(), "account-a").execute("second", buildJsonObject {})
        assertEquals(first.content, second.content)
        assertEquals("second", second.callId)
        assertEquals(1, generated)
        coordinator().bind(tool(), "account-b").execute("private", buildJsonObject {})
        assertEquals(2, generated)
    }

    @Test fun revocationDuringInferencePreservesOriginalContent() = runBlocking {
        var enabled = true
        val assist = AssistCoordinator("goal", { true }, {
            enabled = false
            "[0]"
        }, {}, stillEnabled = { enabled })
        val result = assist.bind(tool()).execute("call", buildJsonObject {})
        assertEquals(ToolResultContent.Json(payload), result.content)
    }

    @Test fun oneTurnSharesTheAttemptLimitAcrossCoordinators() = runBlocking {
        var generated = 0
        val turn = java.util.UUID.randomUUID().toString()
        repeat(3) { index ->
            val assist = AssistCoordinator("goal $index", { true }, {
                dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch()
                generated++
                "[0]"
            }, {}, policy = LocalAssistPolicy(maxAttempts = 1), turnId = turn)
            assist.bind(tool()).execute("call$index", buildJsonObject {})
        }
        assertEquals(1, generated)
    }

    @Test fun rejectedPreparationDoesNotSpendPhysicalAllowance() = runBlocking {
        val states = mutableListOf<AssistTaskState>()
        var preparations = 0
        var dispatched = 0
        val assist = AssistCoordinator("goal", { true }, {
            preparations++
            if (preparations == 1) error("admission rejected before dispatch")
            dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch()
            dispatched++
            "[0]"
        }, {}, policy = LocalAssistPolicy(maxAttempts = 1), status = { states += it })
        val wrapped = assist.bind(tool())
        wrapped.execute("rejected", JsonObject(emptyMap()))
        assertEquals(0, states.last().physicalAttempts)
        wrapped.execute("admitted", JsonObject(emptyMap()))
        wrapped.execute("exhausted", JsonObject(emptyMap()))
        assertEquals(1, dispatched)
        assertEquals(1, states.last().physicalAttempts)
        assertEquals("PHYSICAL_ATTEMPT_ALLOWANCE", states.last().reason)
    }

    @Test fun permissionsAreCheckedAfterQueueAndBeforeCharging() = runBlocking {
        var enabled = true
        val states = mutableListOf<AssistTaskState>()
        var dispatched = false
        val assist = AssistCoordinator("goal", { true }, {
            enabled = false
            dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch()
            dispatched = true
            "[0]"
        }, {}, stillEnabled = { enabled }, status = { states += it })
        assist.bind(tool()).execute("call", JsonObject(emptyMap()))
        assertFalse(dispatched)
        assertEquals(0, states.last().physicalAttempts)
        assertEquals("PERMISSION_OR_RUNTIME_CHANGED", states.last().reason)
    }

    @Test fun explicitProbeUsesProductionSelectionAndKeepsSyntheticOriginals() = runBlocking {
        val probe = AssistTestFixture.tool()
        val original = probe.execute("probe", JsonObject(emptyMap()))
        val assist = AssistCoordinator("water bottles", { true }, { "[0,2,4]" }, {})
        val selected = assist.bind(probe).execute("probe", JsonObject(emptyMap()))
        assertEquals(original.content, selected.content)
        val payload = selected.assistMetadata.single()
        assertEquals(JsonPrimitive(true), payload["assistSelection"])
        assertEquals(3, (payload["assistSelectedRecords"] as JsonArray).size)
    }

    @Test fun cachedSelectionCannotSurviveRuntimeReadinessRevocation() = runBlocking {
        var ready = true
        val assist = AssistCoordinator("goal", { ready }, { "[0]" }, {}, turnId = java.util.UUID.randomUUID().toString())
        val wrapped = assist.bind(tool(), "same-authority")
        wrapped.execute("first", JsonObject(emptyMap()))
        ready = false
        assertEquals(ToolResultContent.Json(payload), wrapped.execute("second", JsonObject(emptyMap())).content)
    }
}
