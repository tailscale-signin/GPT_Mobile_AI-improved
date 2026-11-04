package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChecklistPaginationTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val tools = listOf(AgentToolDefinition("github_context", "Branches", json("""{"properties":{"action":{"const":"branches"},"view_cursor":{"type":"string"}}}""")))
    private val page = json("""{"tool":"github_context","arguments":{"view_cursor":"saved:1"}}""")
    private val recovery = json("""{"tool":"github_context","arguments":{"action":"branches","per_page":5}}""")
    private fun source() = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","per_page":5},"result":{"local_page_complete":false,"continuation_call":$page}}""")
    private fun failed(recipe: JsonObject = recovery) = json("""{"trusted":true,"success":false,"tool":"github_context","arguments":{"view_cursor":"saved:1"},"error_code":"SAVED_VIEW_UNAVAILABLE","result":{"reason":"CAPACITY_EVICTED","recovery_call":$recipe}}""")

    @Test fun unreadLocalPageRunsBeforeAnotherInventoryCanEvictIt() {
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, mapOf("source" to source())))
        assertEquals(page["arguments"], next.arguments)
    }

    @Test fun failedPageRemainsPendingAndReopensExactIssuedSourceOnce() {
        val records = linkedMapOf("source" to source(), "failure" to failed())
        assertEquals(listOf(page), GitHubTestChecklist.pendingPages(records))
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(recovery["arguments"], next.arguments)
        // Restoration after process death must keep the recovery budget spent.
        records["reopened"] = json(source().toString().replace("saved:1", "fresh:1"))
        assertEquals(json("""{"view_cursor":"fresh:1"}"""), GitHubTestChecklist.nextCall("test all tools", tools, records.toMap())?.arguments)
        records["lostAgain"] = json(failed().toString().replace("saved:1", "fresh:1"))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records.toMap()))
        assertFalse(GitHubTestChecklist.pendingPages(records).isEmpty())
    }

    @Test fun sourceRecoveryCannotReplayMutationOrUnauthorizedTool() {
        val mutation = json("""{"tool":"github_commit","arguments":{"action":"branch","branch":"unsafe"}}""")
        val commitTool = AgentToolDefinition("github_commit", "Branch", json("""{"properties":{"action":{"const":"branch"}}}"""))
        for (recipe in listOf(mutation, json("""{"tool":"github_read__other","arguments":{"view_cursor":"foreign:1"}}"""))) {
            val records = mapOf("source" to source(), "failure" to failed(recipe))
            assertEquals(null, GitHubTestChecklist.nextCall("test all tools read only", tools + commitTool, records))
        }
    }

    @Test fun permissionFailureCannotTriggerSavedPageRecovery() {
        val failure = JsonObject(failed() + ("error_code" to JsonPrimitive("PERMISSION_DENIED")))
        val records = mapOf("source" to source(), "failure" to failure)
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(listOf(page), GitHubTestChecklist.pendingPages(records))
    }

    @Test fun requiredLocalFragmentsContinueAfterUnrelatedSamplingBudget() {
        val records = linkedMapOf("source" to source())
        for (i in 1..2) records["page$i"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"view_cursor":"previous:$i"},"result":{}}""")
        assertEquals(page["arguments"], GitHubTestChecklist.nextCall("test all tools", tools, records)?.arguments)
        assertEquals(page["arguments"], GitHubTestChecklist.nextCall("test all tools and all branches", tools, records)?.arguments)
        assertTrue(GitHubTestChecklist.pendingPages(records).isNotEmpty())
    }

    @Test fun sampleBudgetIsBoundedAndExplicitInventoryCanContinue() {
        val remote = json("""{"tool":"github_context","arguments":{"action":"branches","page":2}}""")
        val records = linkedMapOf("source" to json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches"},"result":{"local_page_complete":true,"continuation_call":$remote}}"""))
        for (i in 3..4) records["page$i"] = json("""{"trusted":true,"success":true,"tool":"github_context","arguments":{"action":"branches","page":$i},"result":{}}""")
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all branches", tools, records)?.arguments)
        assertEquals(listOf(remote), GitHubTestChecklist.pendingPages(records))
    }

    @Test fun missingIssueDependencyDoesNotForceUnboundedRemotePagination() {
        val issueTools = listOf(AgentToolDefinition("github_issue", "Issue inventory", json("""{"properties":{"action":{"const":"list"},"page":{"type":"integer"}}}""")))
        val remote = json("""{"tool":"github_issue","arguments":{"action":"list","page":2}}""")
        val records = linkedMapOf<String, JsonObject>()
        records["source"] = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list","page":1},"result":{"continuation_call":$remote}}""")
        for (i in 3..10) {
            records["sample$i"] = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list","page":$i},"result":{}}""")
        }
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", issueTools, records))
        assertEquals(remote["arguments"], GitHubTestChecklist.nextCall("test all tools and all issue history", issueTools, records)?.arguments)
    }

    @Test fun successfulPageClosesPendingContinuation() {
        val success = JsonObject(failed() + ("success" to JsonPrimitive(true)))
        assertTrue(GitHubTestChecklist.pendingPages(mapOf("source" to source(), "read" to success)).isEmpty())
    }
}
