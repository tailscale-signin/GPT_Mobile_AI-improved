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
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", listOf(branches), records, remainingToolCalls = 2))
        assertEquals(json("""{"action":"branches","verify_live":true}"""), next.arguments)
        records["retry"] = JsonObject(cached + ("arguments" to next.arguments))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(branches), records.toMap()))
        records["retry"] = JsonObject(records.getValue("retry") + ("evidence_origin" to JsonPrimitive("LIVE")))
        assertEquals(JsonPrimitive("PASSED"), (GitHubTestChecklist.rows(records, listOf(branches)).single() as JsonObject)["status"])
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
