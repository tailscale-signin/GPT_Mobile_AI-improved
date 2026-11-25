package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import dev.chungjungsoo.gptmobile.data.permissions.DuplicateToolAction
import dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuthorizationOutcomeTest {
    @Test fun childPolicyRejectionIsUnsentAndNeverBecomesAnUnknownWriteOrRepeatedFailure() = runTest {
        var checks = 0
        val child = object : AgentTool {
            override val definition = AgentToolDefinition("github_write__github_api", "", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                checks++
                throw ToolAuthorizationRejected("PERSONAL_DATA_LOCAL_ONLY", "Saved local-only context cannot leave this device")
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(child)
        repeat(4) { attempt ->
            val result = wrapped.execute("attempt-$attempt", buildJsonObject {})
            assertEquals("PERSONAL_DATA_LOCAL_ONLY", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result))
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(ToolResultEnvelope.encode(child.definition.name, result)) as JsonObject
            val execution = envelope.getValue("execution") as JsonObject
            assertEquals(kotlinx.serialization.json.JsonPrimitive(false), execution["retryable"])
        }
        assertEquals(1, checks)
    }

    @Test fun timedOutReadAllowsFreshInvocationButWriteRequiresReconciliation() = runTest {
        for ((name, expected) in listOf("github_search__github_api" to "READ_DEADLINE", "github_write__github_api" to "TIMEOUT_OUTCOME_UNKNOWN")) {
            var dispatches = 0
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition(name, "", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    kotlinx.coroutines.delay(1000)
                    return AgentToolResult(callId, ToolResultContent.Text("late"), false)
                }
            }
            val wrapped = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 10)).bind(tool)
            assertEquals(expected, wrapped.execute("first", buildJsonObject {}).errorCode)
            val retry = wrapped.execute("retry", buildJsonObject {})
            assertEquals(if (expected == "READ_DEADLINE") "READ_DEADLINE" else "RECONCILIATION_REQUIRED", retry.errorCode)
            assertEquals(if (expected == "READ_DEADLINE") 2 else 1, dispatches)
            assertEquals("DUPLICATE_INVOCATION", wrapped.execute("first", buildJsonObject {}).errorCode)
        }
    }

    @Test fun repositoryGraphIndexDeadlineRetainsUnknownOutcomeAndBlocksFreshReplay() = runTest {
        for (name in listOf("repository_graph", "repository_graph__github_api")) {
            for (approvedReadOnly in listOf(false, true)) {
                var queries = 0
                val graph = RepositoryGraphTool(name) {
                    queries++
                    delay(1000)
                    buildJsonObject { put("has_more", false) }
                }
                val measured = MeasuredAgentTool(graph, approvedReadOnly = approvedReadOnly)
                val wrapped = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 10)).bind(measured)
                val arguments = buildJsonObject { put("action", "index") }
                val timedOut = wrapped.execute("index", arguments)
                assertEquals("TIMEOUT_OUTCOME_UNKNOWN", timedOut.errorCode)
                assertTrue(timedOut.dispatched)
                assertEquals(1, queries)
                val receipt = ToolFailureMetadata.receipt(timedOut, name, replaySafe = ToolReplaySafety.isReplayableRead(name, arguments, measured))
                assertEquals(JsonPrimitive(false), receipt.executionReceipt?.get("retryable"))
                assertEquals(JsonPrimitive(true), receipt.executionReceipt?.get("requires_reconciliation"))
                assertEquals(JsonPrimitive(false), receipt.executionReceipt?.get("replay_safe"))
                val envelope = Json.parseToJsonElement(ToolResultEnvelope.encode(name, receipt)) as JsonObject
                assertEquals(JsonPrimitive("UNKNOWN_OUTCOME"), (envelope["execution"] as JsonObject)["state"])
                assertEquals(JsonPrimitive(true), (envelope["error"] as JsonObject)["reconciliationRequired"])
                val retry = wrapped.execute("new-index-call", arguments)
                assertEquals("RECONCILIATION_REQUIRED", retry.errorCode)
                assertFalse(retry.dispatched)
                assertEquals(1, queries)
                // Status remains an independent read after an interrupted index.
                assertEquals("READ_DEADLINE", wrapped.execute("reconcile-status", buildJsonObject { put("action", "status") }).errorCode)
                assertEquals(2, queries)
            }
        }
    }

    @Test fun graphIndexJobControlsCannotAcquireReplayAuthorityFromAReadOnlyWrapper() = runTest {
        for (control in listOf("start", "pause", "resume", "cancel")) {
            var queries = 0
            val graph = RepositoryGraphTool("repository_graph__github_api") {
                queries++
                delay(1000)
                buildJsonObject { put("status", "INDEX_JOB_ACCEPTED") }
            }
            val measured = MeasuredAgentTool(graph, approvedReadOnly = true)
            val wrapped = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 10)).bind(measured)
            val arguments = buildJsonObject {
                put("action", "index")
                put(
                    "options",
                    buildJsonObject {
                        put("background", true)
                        put("job_action", control)
                        put("job_id", "observed-job")
                        put("job_generation", 2)
                    }
                )
            }
            assertFalse(ToolReplaySafety.isReplayableRead(measured.definition.name, arguments, measured))
            val native = JsonObject(arguments + mapOf("action" to JsonPrimitive("repository_graph"), "graph_action" to JsonPrimitive("index")))
            assertFalse(ToolReplaySafety.isReplayableRead("github", native, measured))
            assertEquals("TIMEOUT_OUTCOME_UNKNOWN", wrapped.execute("first-$control", arguments).errorCode)
            assertEquals("RECONCILIATION_REQUIRED", wrapped.execute("retry-$control", arguments).errorCode)
            assertEquals(1, queries)
        }
    }

    @Test fun graphStatusAndQueryRetainBoundedReadRetriesThroughTheActualWrapper() = runTest {
        for (name in listOf("repository_graph", "repository_graph__github_api")) {
            for (action in listOf("status", "query")) {
                var queries = 0
                val measured = MeasuredAgentTool(
                    RepositoryGraphTool(name) {
                        queries++
                        delay(1000)
                        buildJsonObject {}
                    },
                    approvedReadOnly = true
                )
                val wrapped = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 10)).bind(measured)
                val arguments = buildJsonObject { put("action", action) }
                assertTrue(ToolReplaySafety.isReplayableRead(name, arguments, measured))
                for (call in listOf("first", "second")) {
                    val result = ToolFailureMetadata.receipt(wrapped.execute(call, arguments), name, replaySafe = true)
                    assertEquals("READ_DEADLINE", result.errorCode)
                    assertEquals(JsonPrimitive(true), result.executionReceipt?.get("retryable"))
                    assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                }
                assertEquals("REPEATED_FAILURE", wrapped.execute("third", arguments).errorCode)
                assertEquals(2, queries)
            }
        }
    }

    @Test fun readTransportExceptionsAllowOnlyBoundedRetries() = runTest {
        var dispatches = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read__github_api", "", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                throw java.io.IOException("connection reset")
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool)
        assertEquals("READ_FAILED", wrapped.execute("first", buildJsonObject {}).errorCode)
        assertEquals("READ_FAILED", wrapped.execute("retry", buildJsonObject {}).errorCode)
        assertEquals("REPEATED_FAILURE", wrapped.execute("third", buildJsonObject {}).errorCode)
        assertEquals(2, dispatches)
    }

    @Test fun permissionDenialStopsSameToolWithChangedArgumentsForThisTurn() = runTest {
        var authorizations = 0
        var dispatches = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("web_search", "", buildJsonObject { })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool, authorize = { _, _ ->
            authorizations++
            false
        })
        val first = wrapped.execute("first", buildJsonObject { })
        val second = wrapped.execute("second", buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("different")) })
        assertEquals("AUTH_REQUIRED", first.errorCode)
        assertEquals("AUTH_REQUIRED", second.errorCode)
        assertEquals(1, authorizations)
        assertEquals(0, dispatches)
    }

    @Test fun duplicateApprovalsKeepThePriorStateAndGroupOnlyMatchingLocalRefusals() = runTest {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("mcp_lookup", "lookup", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Duplicate was dispatched")
        }
        var state = "COMPLETED"
        val budget = ToolExecutionBudget(AgentRunLimits())
        val wrapped = budget.bind(tool, authorize = { _, _ -> throw DuplicateToolAction(state) })
        suspend fun refusal(id: String, connection: String = "search"): ToolReportDiagnostics.PrecheckIdentity {
            val args = buildJsonObject { put("query", "private") }
            val result = wrapped.execute(id, args)
            assertFalse(result.dispatched)
            assertEquals("DUPLICATE_ACTION", result.errorCode)
            assertEquals(JsonPrimitive(state), result.executionReceipt?.get("previous_approval_state"))
            val event = ToolEvent("event-$id", "run", 1, id, connection, null, tool.definition.name, tool.definition.name, args.toString(), null, null, ToolEventStatus.RUNNING)
            return ToolReportDiagnostics.fields(event, result)["local_precheck_identity"] as ToolReportDiagnostics.PrecheckIdentity
        }
        val completed = refusal("first")
        assertEquals(completed, refusal("second"))
        assertFalse(completed == refusal("third", "another-connection"))
        state = "OUTCOME_UNKNOWN"
        assertFalse(completed == refusal("fourth"))
        assertEquals(0, budget.usedCalls())
    }

    @Test fun duplicateActionIsNotReportedAsPermissionDenialAndNeverExecutes() = runTest {
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "", buildJsonObject { })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("written"), false)
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool) { _, _ ->
            throw ToolAuthorizationRejected("DUPLICATE_ACTION", "A matching action already exists")
        }
        val result = wrapped.execute("duplicate", JsonObject(emptyMap()))
        assertEquals("DUPLICATE_ACTION", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, dispatched)
    }
}
