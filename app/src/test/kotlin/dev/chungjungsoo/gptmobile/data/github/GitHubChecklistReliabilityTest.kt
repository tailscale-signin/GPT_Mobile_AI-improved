package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistReliabilityTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun tool(name: String, actions: String) = AgentToolDefinition(name, name, json("""{"properties":{"action":{"enum":[$actions]},"verify_live":{"type":"boolean"},"view_cursor":{"type":"string"},"branch":{"type":"string"},"ref":{"type":"string"},"from_branch":{"type":"string"},"expected_head_sha":{"type":"string"},"refresh":{"type":"boolean"}}}"""))
    private val branches = tool("github_context", "\"branches\"")
    private val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
    private val fragmented = json("""{"trusted":true,"success":true,"dispatched":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"items":[{"name":"main"}],"has_more":true},"view":{"local_page_complete":false,"continuation_call":{"tool":"github_context","arguments":{"view_cursor":"saved:1"}}},"retained_result_complete":true,"result_sha256":"source-digest","retained_remote_continuations":[{"call":$remote}]}""")

    @Test fun completeRetainedSourceSkipsDisplayFragmentsButKeepsExactRemoteRecipe() {
        val receipts = mapOf("source" to fragmented)
        assertEquals(listOf(remote), GitHubTestChecklist.pendingPages(receipts))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all branches", listOf(branches), receipts)?.arguments)
        // Older/partial evidence still needs the exact saved display pages.
        val legacy = JsonObject(fragmented - "retained_result_complete")
        assertEquals(json("""{"view_cursor":"saved:1"}"""), GitHubTestChecklist.pendingPages(mapOf("source" to legacy)).single()["arguments"])
    }

    @Test fun reserveStopsAcquisitionWithoutExpandingTheCeiling() {
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), emptyMap(), remainingToolCalls = 0))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), emptyMap(), remainingToolCalls = 2))
        assertNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(branches), emptyMap(), remainingToolCalls = 3))
        val checkpoint = GitHubTestChecklist.checkpoint("test all tools", emptyMap(), listOf(branches), remainingToolCalls = 2)
        assertEquals(JsonPrimitive(false), (checkpoint["stage_budget"] as JsonObject)["acquisition_allowed"])
        assertTrue((checkpoint["resume_plan"] as JsonObject).containsKey("pending_capabilities"))
        val historical = mapOf("call" to json("""{"tool_budget":{"used":98,"limit":100,"configured":102,"final_response_reserved":2}}"""))
        assertEquals(2, GitHubChecklistBudget.remaining(historical))
        assertEquals(0, GitHubChecklistBudget.remaining(historical, current = 0))
    }

    @Test fun unrequestedCiMutationsAreConditionalWithoutAWorkflowRunOrJob() {
        val ci = tool("github_ci", "\"runs\",\"dispatch\",\"rerun\",\"cancel\"")
        val rows = GitHubTestChecklist.rows(emptyMap(), listOf(ci), "Test GitHub integration coverage").filterIsInstance<JsonObject>()
        for (action in listOf("dispatch", "rerun", "cancel")) {
            val row = rows.single { it["id"] == JsonPrimitive("github_ci:$action") }
            assertEquals(JsonPrimitive("NOT_REQUESTED"), row["status"])
            assertEquals(JsonPrimitive(false), row["required"])
            assertEquals(JsonPrimitive("CONDITIONAL_RESOURCE_NOT_PRESENT"), row["applicability"])
            assertEquals(JsonPrimitive("OUT_OF_SCOPE"), row["blocker_code"])
        }
    }

    @Test fun existingOwnedFixtureCleanupRunsBeforeOptionalAcquisition() {
        val sha = "a".repeat(40)
        val fixture = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
        val source = json("""{"receipt_id":"source","binding":"task","repository":"o/r","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}}""")
        val creation = json("""{"trusted":true,"success":true,"tool":"github_commit","arguments":{"action":"branch","branch":"$fixture"},"result":{"created":true,"receipt_verified":true,"head_sha":"$sha"}}""")
        val tools = listOf(tool("github_context", "\"status\",\"branches\",\"resolve\",\"branch_dependencies\""), tool("github_commit", "\"branch\",\"delete_branch\""))
        val call = GitHubTestChecklist.nextCall("test all tools", tools, linkedMapOf("source" to source, "created" to creation), remainingToolCalls = 2)
        assertEquals(json("""{"action":"resolve","ref":"main","refresh":true}"""), call?.arguments)
        // Do not create a new fixture without its cleanup authorization.
        val createOnly = listOf(tools.first(), tool("github_commit", "\"branch\""))
        assertEquals(null, GitHubSelfTestFixtures.next("test all tools", createOnly, mapOf("source" to source)))
        assertEquals(null, GitHubSelfTestFixtures.next("test all tools", tools, mapOf("source" to source), allowCreate = false))
    }

    @Test fun cachedCapabilityHasOneAuthorizedLiveRefreshAndCannotLoopOnResume() {
        val cached = json("""{"receipt_id":"cached","trusted":true,"success":true,"dispatched":false,"evidence_origin":"CACHED","tool":"github_context","arguments":{"action":"branches"},"result":{}}""")
        val records = linkedMapOf("cached" to cached)
        val row = GitHubTestChecklist.rows(records, listOf(branches)).single() as JsonObject
        assertEquals(JsonPrimitive("CACHED"), row["status"])
        assertEquals(JsonPrimitive(false), row["live_verified"])
        assertFalse(GitHubTestChecklist.apiComplete("test all tools", records, listOf(branches)))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), records, remainingToolCalls = 0))
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(branches), records, remainingToolCalls = 2))
        assertEquals(json("""{"action":"branches","verify_live":true}"""), next.arguments)
        records["retry"] = JsonObject(cached + ("arguments" to next.arguments))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), records.toMap()))
        records["retry"] = JsonObject(records.getValue("retry") + ("evidence_origin" to JsonPrimitive("LIVE")))
        assertEquals(JsonPrimitive("PASSED"), (GitHubTestChecklist.rows(records, listOf(branches)).single() as JsonObject)["status"])
    }

    @Test fun automaticInventoryUsesOneLiveAttemptAndDoesNotScheduleARedundantRefresh() {
        val call = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(branches), emptyMap()))
        assertEquals(JsonPrimitive(true), call.arguments["verify_live"])
        val receipt = json("""{"trusted":true,"success":true,"tool":"github_context","dispatched":true,"evidence_origin":"LIVE","arguments":${call.arguments},"result":{"has_more":false}}""")
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), mapOf("live" to receipt)))
    }

    @Test fun oneLiveDefinitionPageQualifiesTransportOnlyForTheSamePinnedTreeAndAuthority() {
        val sha = "a".repeat(40)
        val ci = tool("github_ci", "\"definitions\"")
        val cached = json("""{"binding":"task","repository":"o/r","trusted":true,"success":true,"tool":"github_ci","evidence_origin":"CACHED","arguments":{"action":"definitions","commit_sha":"$sha","index_cursor":4},"result":{}}""")
        val live = JsonObject(cached + mapOf("evidence_origin" to JsonPrimitive("LIVE"), "arguments" to json("""{"action":"definitions","commit_sha":"$sha","index_cursor":8,"verify_live":true}""")))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(ci), linkedMapOf("cached" to cached, "live" to live)))
        val mismatches = listOf(
            JsonObject(live + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(live + ("repository" to JsonPrimitive("o/other"))),
            JsonObject(live + (GitHubPolicyReadRecovery.AUTHORITY to JsonPrimitive("b".repeat(64)))),
            JsonObject(live + ("arguments" to json("""{"action":"definitions","commit_sha":"${"b".repeat(40)}","index_cursor":8,"verify_live":true}""")))
        )
        mismatches.forEach { other ->
            val refresh = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(ci), linkedMapOf("cached" to cached, "other" to other)))
            assertEquals(JsonPrimitive(sha), refresh.arguments["commit_sha"])
            assertEquals(JsonPrimitive(4), refresh.arguments["index_cursor"])
            assertEquals(JsonPrimitive(true), refresh.arguments["verify_live"])
        }
    }

    @Test fun approvedFixtureStartsBeforeOptionalReadsButNeverWithoutCleanupReserve() {
        val sha = "a".repeat(40)
        val source = json("""{"receipt_id":"source","binding":"task","repository":"o/r","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}}""")
        val tools = listOf(tool("github_context", "\"status\",\"resolve\",\"branch_dependencies\""), tool("github_commit", "\"branch\",\"delete_branch\""))
        val ready = json("""{"state":"READY"}""")
        val admitted = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source), remainingToolCalls = 6, fixturePreflight = ready))
        assertEquals("github_commit", admitted.name)
        assertEquals(JsonPrimitive("branch"), admitted.arguments["action"])
        assertEquals(JsonPrimitive(false), admitted.arguments["select_created_branch"])
        for (remaining in listOf(3, 5)) {
            val optional = GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source), remainingToolCalls = remaining, fixturePreflight = ready)
            assertEquals("github_context", optional?.name)
            assertEquals(JsonPrimitive("resolve"), optional?.arguments?.get("action"))
        }
        val manual = GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source), remainingToolCalls = 64, fixturePreflight = json("""{"state":"CONDITIONAL"}"""))
        assertEquals("github_context", manual?.name)
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source), remainingToolCalls = 2, fixturePreflight = ready))
    }

    @Test fun cachedReadAcceptanceExplicitlyStatesItsResponseScope() {
        val tool = tool("github_context", "\"index\"")
        val cached = json("""{"receipt_id":"cached","trusted":true,"success":true,"dispatched":false,"evidence_origin":"CACHED","tool":"github_context","arguments":{"action":"index"},"result":{}}""")
        val row = GitHubTestChecklist.rows(mapOf("cached" to cached), listOf(tool), "test all tools").single() as JsonObject
        assertEquals(JsonPrimitive("PASS"), row["acceptance_state"])
        assertEquals(JsonPrimitive("RETAINED_RESPONSE"), row["acceptance_basis"])
        assertEquals(JsonPrimitive(false), row["live_verified"])
        assertEquals(JsonPrimitive(false), row["requires_live_transport"])
        val ledger = GitHubAcceptanceLedger.snapshot(capabilities = kotlinx.serialization.json.JsonArray(listOf(row)))
        val retained = (ledger["capabilities"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("RETAINED_RESPONSE"), retained["acceptance_basis"])
    }

    @Test fun mixedNonLiveSuccessesCannotQualifyARemoteCapability() {
        for (otherOrigin in listOf("MOCKED", "LOCAL", "NOT_TESTED")) {
            val cached = json("""{"trusted":true,"success":true,"evidence_origin":"CACHED","tool":"github_context","arguments":{"action":"branches"},"result":{}}""")
            val other = JsonObject(cached + ("evidence_origin" to JsonPrimitive(otherOrigin)))
            val records = mapOf("cached" to cached, "other" to other)
            val row = GitHubTestChecklist.rows(records, listOf(branches)).single() as JsonObject
            assertEquals(JsonPrimitive("CACHED"), row["status"])
            assertFalse(GitHubTestChecklist.apiComplete("test all tools", records, listOf(branches)))
        }
    }

    @Test fun explicitTransportProvenanceTakesPriorityOverLegacyDispatchedFlag() {
        assertEquals("LOCAL", GitHubEvidenceOrigin.classify(true, false, json("""{"phase":"LOCAL_OR_REPLAYED","read_requests":0,"mutation_requests":0}""")))
        assertEquals("CACHED", GitHubEvidenceOrigin.classify(true, false, json("""{"phase":"LOCAL_OR_REPLAYED","cached_read_observations":1}""")))
        assertEquals("CACHED", GitHubEvidenceOrigin.classify(true, true, json("""{"read_requests":1}""")))
        assertEquals("LIVE", GitHubEvidenceOrigin.classify(false, false, json("""{"read_requests":1}""")))
        assertEquals("NOT_TESTED", GitHubEvidenceOrigin.classify(false, false, null))
    }
}
