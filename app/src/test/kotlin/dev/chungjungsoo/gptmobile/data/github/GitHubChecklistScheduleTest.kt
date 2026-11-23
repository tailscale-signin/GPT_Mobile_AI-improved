package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistScheduleTest {
    private val discovery = AgentToolDefinition("discover_available_tools", "Discover", json("""{"type":"object","properties":{"names":{"type":"array","items":{"type":"string"}}},"additionalProperties":false}"""))
    private val context = GitHubOperationTool.definition("github_context")
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun call(name: String = context.name, action: String = "repository") = ProviderEvent.ToolCall("$name-$action", name, json("""{"action":"$action"}"""))
    private fun select(
        candidates: List<ProviderEvent.ToolCall> = listOf(call()),
        advertised: List<AgentToolDefinition> = listOf(discovery),
        authorized: List<AgentToolDefinition> = listOf(context, discovery),
        receipts: Map<String, JsonObject> = emptyMap(),
        planned: ProviderEvent.ToolCall? = null,
        acquire: Boolean = true
    ) = GitHubChecklistSchedule.select(authorized, advertised, receipts, candidates, planned, null, acquire)

    @Test fun sixPreparedIndependentReadsContinueAfterUnrelatedOperationFailures() {
        val actions = listOf("resolve", "index", "efficiency", "capabilities", "repository", "recovery")
        val tools = listOf(context)
        val receipts = linkedMapOf("failed-pr" to json("""{"trusted":true,"success":false,"dispatched":false,"tool":"github_pr","arguments":{"action":"agent_merge_status","pull_number":2254,"expected_head_sha":"${"a".repeat(40)}"},"error_code":"INVALID_ARGUMENTS"}"""))
        val executed = mutableListOf<String>()
        repeat(6) {
            val candidates = GitHubSelfTestReads.available(tools, receipts.values.toList())
            val plan = select(candidates, tools, tools, receipts)
            assertEquals(JsonPrimitive(6 - it), plan.snapshot["admitted_candidate_count"])
            val next = requireNotNull(plan.next)
            executed += (next.arguments.getValue("action") as JsonPrimitive).content
            receipts[next.callId] = json("""{"trusted":true,"success":true,"tool":"${next.name}","arguments":${next.arguments},"result":{}}""")
        }
        assertEquals(actions.toSet(), executed.toSet())
        assertTrue(GitHubSelfTestReads.available(tools, receipts.values.toList()).isEmpty())
        assertEquals(JsonPrimitive(false), receipts.getValue("failed-pr")["success"])
    }

    @Test fun hiddenReadyToolIsDiscoveredByExactNameDespiteAnEarlierReceiptOrCatalogFailure() {
        val receipts = mapOf(
            "catalog" to json("""{"trusted":true,"success":false,"tool":"discover_available_tools","arguments":{},"error_code":"CATALOG_CONTRACT_CHANGED"}"""),
            "old-action" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"resolve"}}""")
        )
        val plan = select(receipts = receipts)
        assertEquals(discovery.name, plan.next?.name)
        assertEquals(json("""{"names":["github_context"]}"""), plan.next?.arguments)
        assertEquals(JsonPrimitive("EXACT_SCHEMA_DISCOVERY_REQUIRED"), plan.snapshot.getValue("candidates").jsonArray.single().jsonObject["admission"])
        assertEquals(JsonPrimitive(true), plan.snapshot["catalog_denominator_unchanged"])
    }

    @Test fun currentIndependentReadExecutesWhileAnotherToolNeedsDiscovery() {
        val hidden = context.copy(name = "github_context__hidden")
        val visible = call()
        val plan = select(listOf(call(hidden.name), visible), listOf(discovery, context), listOf(discovery, hidden, context))
        assertEquals(visible, plan.next)
        assertEquals(JsonPrimitive(1), plan.snapshot["admitted_candidate_count"])
        assertEquals(JsonPrimitive(1), plan.snapshot["blocked_candidate_count"])
    }

    @Test fun unsuccessfulAndNonExposedSchemaAcquisitionRetainReasonsWithoutRetryLoops() {
        for (success in listOf(false, true)) {
            val receipts = mapOf("discovered" to json("""{"receipt_id":"discovered","trusted":true,"success":$success,"tool":"discover_available_tools","arguments":{"names":["github_context"]},"error_code":"SCHEMA_UNAVAILABLE"}"""))
            val plan = select(receipts = receipts)
            assertNull(plan.next)
            val row = plan.snapshot.getValue("candidates").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(if (success) "TOOL_NOT_ADVERTISED_AFTER_DISCOVERY" else "SCHEMA_DISCOVERY_FAILED"), row["admission"])
            assertEquals(JsonPrimitive("discovered"), row["discovery_receipt_id"])
            assertEquals(JsonPrimitive(false), row["selected"])
            assertEquals(JsonPrimitive(false), row["discovery_selected"])
        }
    }

    @Test fun budgetExcludedSchemaGetsOneExactActionRetryAndThenExecutes() {
        val actionDiscovery = discovery.copy(inputSchema = json("""{"type":"object","properties":{"names":{"type":"array","items":{"type":"string"}},"action":{"type":"string"}},"additionalProperties":false}"""))
        val receipts = linkedMapOf("batch" to json("""{"trusted":true,"success":true,"tool":"discover_available_tools","arguments":{"names":["github_ci","github_context"]},"result":{"schema_budget_excluded":["github_context"]}}"""))
        val retry = select(advertised = listOf(actionDiscovery), receipts = receipts)
        assertEquals(json("""{"names":["github_context"],"action":"repository"}"""), retry.next?.arguments)
        assertEquals(JsonPrimitive("SCHEMA_BUDGET_RECOVERY_REQUIRED"), retry.snapshot.getValue("candidates").jsonArray.single().jsonObject["admission"])
        receipts["scoped"] = json("""{"trusted":true,"success":true,"tool":"discover_available_tools","arguments":${retry.next!!.arguments},"result":{"schema_budget_excluded":[]}}""")
        assertEquals(call(), select(advertised = listOf(actionDiscovery, context), receipts = receipts).next)
        assertNull(select(advertised = listOf(actionDiscovery), receipts = receipts).next)
    }

    @Test fun repeatedBudgetExclusionExhaustsItsRecoveryWithoutLooping() {
        val receipts = linkedMapOf("batch" to json("""{"trusted":true,"success":true,"tool":"discover_available_tools","arguments":{"names":["github_ci","github_context"]},"result":{"schema_budget_excluded":["github_context"]}}"""))
        val retry = requireNotNull(select(receipts = receipts).next)
        assertEquals(json("""{"names":["github_context"]}"""), retry.arguments)
        receipts["scoped"] = json("""{"trusted":true,"success":true,"tool":"discover_available_tools","arguments":${retry.arguments},"result":{"schema_budget_excluded":["github_context"]}}""")
        val exhausted = select(receipts = receipts)
        assertNull(exhausted.next)
        assertEquals(JsonPrimitive("SCHEMA_BUDGET_RECOVERY_EXHAUSTED"), exhausted.snapshot.getValue("candidates").jsonArray.single().jsonObject["admission"])
        assertNull(select(receipts = receipts, acquire = false).next)
    }

    @Test fun anotherProjectedActionCanAcquireItsOwnSchemaOnlyOnce() {
        val actionDiscovery = discovery.copy(inputSchema = json("""{"type":"object","properties":{"names":{"type":"array","items":{"type":"string"}},"action":{"type":"string"}},"additionalProperties":false}"""))
        val receipts = linkedMapOf("resolve" to json("""{"trusted":true,"success":true,"tool":"discover_available_tools","arguments":{"names":["github_context"],"action":"resolve"},"result":{}}"""))
        val retry = select(advertised = listOf(actionDiscovery), receipts = receipts)
        assertEquals(json("""{"names":["github_context"],"action":"repository"}"""), retry.next?.arguments)
        receipts["repository"] = json("""{"trusted":true,"success":false,"tool":"discover_available_tools","arguments":${retry.next!!.arguments},"error_code":"TOOL_NOT_AUTHORIZED"}""")
        assertNull(select(advertised = listOf(actionDiscovery), receipts = receipts).next)
    }

    @Test fun authorizedAdvertisedFacadeIsPreferredOnlyWithinTheSelectedConnection() {
        val full = GitHubOperationTool.definition("github_context", "github_context__github_api")
        val inspect = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__github_api")
        val other = inspect.copy(name = "github_context_inspect__other")
        val request = call(full.name)
        val tools = listOf(discovery, full, inspect, other)
        val admitted = select(listOf(request), listOf(discovery, other, inspect), tools)
        assertEquals(inspect.name, admitted.next?.name)
        assertEquals(request.arguments, admitted.next?.arguments)
        assertEquals(inspect.name, select(listOf(request), listOf(inspect), tools, planned = request, acquire = false).next?.name)
        assertEquals(discovery.name, select(listOf(request), listOf(discovery, other), tools).next?.name)
        assertEquals(discovery.name, select(listOf(request), listOf(discovery, inspect), listOf(discovery, full)).next?.name)
        assertNull(select(listOf(request), listOf(inspect), listOf(inspect)).next)
    }

    @Test fun completedFacadeReadDoesNotReappearAsAFullToolCandidate() {
        val full = GitHubOperationTool.definition("github_context", "github_context__github_api")
        val inspect = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__github_api")
        val receipt = json("""{"trusted":true,"success":true,"tool":"${inspect.name}","arguments":{"action":"repository"},"result":{}}""")
        val remaining = GitHubSelfTestReads.available(listOf(full, inspect), listOf(receipt))
        assertFalse(remaining.any { it.arguments["action"] == JsonPrimitive("repository") })
        val otherConnection = JsonObject(receipt + ("tool" to JsonPrimitive("github_context_inspect__other")))
        assertTrue(GitHubSelfTestReads.available(listOf(full, inspect), listOf(otherConnection)).any { it.arguments["action"] == JsonPrimitive("repository") })
    }

    @Test fun unavailableDiscoveryAndInvalidActionContractsNeverDispatchOrVanish() {
        val absent = select(advertised = emptyList())
        assertNull(absent.next)
        assertEquals(JsonPrimitive("DISCOVERY_TOOL_NOT_ADVERTISED"), absent.snapshot.getValue("candidates").jsonArray.single().jsonObject["admission"])
        val pr = GitHubOperationTool.definition("github_pr")
        val invalid = ProviderEvent.ToolCall("invalid", pr.name, json("""{"action":"agent_merge_status","pull_number":2254,"expected_head_sha":"${"a".repeat(40)}"}"""))
        val rejected = select(listOf(invalid), listOf(discovery, pr), listOf(discovery, pr))
        assertNull(rejected.next)
        assertEquals(JsonPrimitive("ACTION_ARGUMENTS_REJECTED"), rejected.snapshot.getValue("candidates").jsonArray.single().jsonObject["admission"])
    }

    @Test fun verificationAndCleanupReservesCannotFundNewReadsOrSchemaDiscovery() {
        assertNull(select(advertised = listOf(context), acquire = false).next)
        assertNull(select(acquire = false).next)
        val admittedVerification = call()
        assertEquals(admittedVerification, select(advertised = listOf(context), planned = admittedVerification, acquire = false).next)
        assertNull(select(planned = admittedVerification, acquire = false).next)
    }

    @Test fun finalAdmissionPlanMustMatchTheExactCurrentReceiptContents() {
        val original = mapOf("same-id" to json("""{"success":false}"""))
        val plan = select(receipts = original).snapshot
        assertEquals(plan, GitHubChecklistSchedule.current(plan, original))
        assertNull(GitHubChecklistSchedule.current(plan, original + ("new-id" to json("{}"))))
        assertNull(GitHubChecklistSchedule.current(plan, mapOf("same-id" to json("""{"success":true}"""))))
        assertFalse(plan.getValue("candidates").jsonArray.isEmpty())
    }
}
