package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedToolExecutionBudgetTest {
    @Test
    fun localCatalogSelectionWorksWithoutExternalWorkAllowance() = runBlocking {
        val fixture = fixture(0)
        val catalog = ProgressiveToolCatalog(listOf(AgentToolDefinition("read_url", "Read a page", JsonObject(emptyMap()))), "research")
        val result = fixture.wrapper().bind(catalog).execute("discover", kotlinx.serialization.json.buildJsonObject { put("query", JsonPrimitive("read_url")) })
        assertFalse(result.isError)
        assertFalse(result.dispatched)
        assertTrue(fixture.store.open(fixture.context).snapshot().reservations.isEmpty())
    }

    @Test
    fun nativeAndRepositoryWrappersChargeOnePhysicalCallOnce() = runBlocking {
        val fixture = fixture(2)
        var executions = 0
        val raw = tool("leaf") { callId, _ ->
            executions++
            result(callId)
        }
        val inner = fixture.wrapper().bind(raw)
        val outer = fixture.wrapper().bind(inner)
        assertFalse(outer.execute("call", queries(2)).isError)
        assertEquals(1, executions)
        val snapshot = fixture.store.open(fixture.context).snapshot()
        assertEquals(2L, snapshot.charged.toolOperations)
        assertEquals(1, snapshot.reservations.size)
    }

    @Test
    fun coordinatorChargesAuthorizedChildrenIncludingTheirBatchUnits() = runBlocking {
        val fixture = fixture(4)
        var executions = 0
        val leaf = fixture.wrapper().bind(
            tool("leaf") { callId, _ ->
                executions++
                result(callId)
            }
        )
        val coordinator = object : AgentTool {
            override val definition = AgentToolDefinition("coordinator", "Bound child work", JsonObject(emptyMap()))
            override val managesExecutionBudget = true
            override val coordinatesChildWork = true
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertFalse(leaf.execute("$callId-one", queries(3)).isError)
                assertFalse(leaf.execute("$callId-two", queries(1)).isError)
                return result(callId)
            }
        }
        assertFalse(fixture.wrapper().bind(coordinator).execute("coordinated", JsonObject(emptyMap())).isError)
        val snapshot = fixture.store.open(fixture.context).snapshot()
        assertEquals(2, executions)
        assertEquals(2, snapshot.reservations.size)
        assertEquals(4L, snapshot.charged.toolOperations)
    }

    @Test
    fun zeroAllowanceAndFinalizationRolesMakeZeroPhysicalCalls() = runBlocking {
        for (finalization in listOf(false, true)) {
            val fixture = fixture(if (finalization) 10 else 0)
            var executions = 0
            val bound = fixture.wrapper().bind(
                tool("leaf") { callId, _ ->
                    executions++
                    result(callId)
                }
            )
            val outcome = if (finalization) {
                withContext(CombinedRunScope(fixture.context.forRole(CombinedRunRole.SYNTHESIS))) { bound.execute("call", JsonObject(emptyMap())) }
            } else {
                bound.execute("call", JsonObject(emptyMap()))
            }
            assertTrue(outcome.isError)
            assertFalse(outcome.dispatched)
            assertEquals(0, executions)
            assertTrue(fixture.store.open(fixture.context).snapshot().reservations.isEmpty())
        }
    }

    @Test
    fun childRoleOverridesParentBindingAndConfirmedRejectionRefundsWork() = runBlocking {
        val fixture = fixture(2)
        val child = fixture.context.forRole(CombinedRunRole.VERIFIER, profileUid = "b")
        val bound = fixture.wrapper().bind(tool("leaf") { callId, _ -> ToolResultEnvelope.error(callId, "AUTH_REQUIRED", "Revoked", dispatched = false) })
        val outcome = withContext(CombinedRunScope(child)) { bound.execute("call", JsonObject(emptyMap())) }
        assertTrue(outcome.isError)
        val snapshot = fixture.store.open(fixture.context).snapshot()
        assertEquals(0L, snapshot.charged.toolOperations)
        assertEquals(CombinedRunRole.VERIFIER, snapshot.reservations.values.single().role)
        assertEquals(CombinedReservationStatus.RELEASED, snapshot.reservations.values.single().status)
    }

    @Test
    fun paperCoordinatorProgressCountsReadSourceWithoutCountingItsCitationLinks() = runBlocking {
        val fixture = fixture(10)
        val budget = fixture.store.open(fixture.context)
        val item = requireNotNull(budget.assignEvidenceWork(fixture.context, "Test combined work", false))
        budget.recordDiscovery(item, "https://example.org/read https://example.org/cited")
        val before = requireNotNull(budget.snapshot().pendingWork[item]).remainingUnits
        val reader = object : AgentTool {
            override val definition = AgentToolDefinition("deep_research_read_paper", "Read one source", JsonObject(emptyMap()))
            override val coordinatesChildWork = true
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(
                    JsonObject(
                        mapOf(
                            "retrievalStatus" to JsonPrimitive("read"),
                            "canonicalUrl" to JsonPrimitive("https://example.org/read"),
                            "text" to JsonPrimitive("This article cites https://example.org/cited but that reference was not read.")
                        )
                    )
                ),
                false
            )
        }
        assertFalse(fixture.wrapper().bind(reader).execute("read", JsonObject(mapOf("identifier" to JsonPrimitive("doi:10.1234/example")))).isError)
        assertEquals(before - 1, budget.snapshot().pendingWork[item]?.remainingUnits)
        assertEquals(1, budget.snapshot().progressFingerprints.size)
    }

    private fun tool(name: String, execute: suspend (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Test physical work", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute.invoke(callId, arguments)
    }

    private fun result(callId: String) = AgentToolResult(callId, ToolResultContent.Text("Completed"), false)
    private fun queries(size: Int) = JsonObject(mapOf("queries" to JsonArray((1..size).map { JsonPrimitive("query $it") })))
    private fun fixture(hardTools: Long): Fixture {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 100), listOf("a"), mapOf("a" to "model"), "Test combined work", acceptedAtMillis = 1)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested, hardLimits = CombinedHardLimits(toolOperations = hardTools)), java.util.UUID.randomUUID().toString(), profileUid = "a", persistBudget = false)
        return Fixture(context, CombinedTurnBudgetStore.inMemory())
    }

    private data class Fixture(val context: CombinedExecutionContext, val store: CombinedTurnBudgetStore) {
        fun wrapper() = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 20, combined = context), combined = context, combinedBudgets = store)
    }
}
