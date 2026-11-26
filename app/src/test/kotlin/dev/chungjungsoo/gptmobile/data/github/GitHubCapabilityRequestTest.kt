package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCapabilityRequestTest {
    @Test fun distinguishesCapabilitiesFromRequestsToExecute() {
        val expected = mapOf(
            "What tools can you use in this chat?" to GitHubCapabilityRequest.Scope.TOOLS,
            "What tools can you use here?" to GitHubCapabilityRequest.Scope.TOOLS,
            "Which tools are enabled here?" to GitHubCapabilityRequest.Scope.TOOLS,
            "List all tools and features" to GitHubCapabilityRequest.Scope.ALL,
            "Which GitHub actions are available?" to GitHubCapabilityRequest.Scope.GITHUB,
            "Can you inspect my PRs?" to GitHubCapabilityRequest.Scope.GITHUB,
            "List app features matching voice page 2" to GitHubCapabilityRequest.Scope.FEATURES
        )
        expected.forEach { (query, scope) -> assertEquals(query, scope, GitHubCapabilityRequest.from(query)?.scope) }
        val filtered = requireNotNull(GitHubCapabilityRequest.from("List app features matching voice page 2"))
        assertEquals("voice", filtered.filter)
        assertEquals(2, filtered.page)
        for (query in listOf("Can you inspect PR #42?", "Inspect my PRs", "List tools and then deploy", "List tools for GitHub and publish", "Which files implement the GitHub actions screen?", "List features\nThen merge it")) {
            assertNull(query, GitHubCapabilityRequest.from(query))
        }
    }

    @Test fun sourceResearchPlansEvidenceWithoutRunningTheFeatureOrEditingIt() {
        val goal = "Where is the search the web feature implemented in the repository?"
        assertTrue(GitHubCodeResearch.matches(goal))
        for (task in listOf("Locate the implementation and then fix the bug", "Where is search implemented and could you fix it?", "Where is search implemented? Could you please update it?")) {
            assertFalse(task, GitHubCodeResearch.matches(task))
        }
        val steps = GitHubOperationPlanner.plan(goal).map { it.jsonObject["id"] }
        assertEquals(listOf("resolve_context", "search_repository", "read_ranges").map(::JsonPrimitive), steps)
        assertEquals(JsonPrimitive("capability_inventory"), GitHubOperationPlanner.plan("Can you inspect my PRs?").single().jsonObject["id"])
    }

    @Test fun answersFromTheVisibleProjectionAndVerifiedDiscoveryWithoutInferringPermissions() {
        val request = requireNotNull(GitHubCapabilityRequest.from("Can you inspect my PRs?"))
        val pr = GitHubOperationTool.definition("github_pr_inspect", "github_pr_inspect__mine")
        val absent = GitHubCapabilityAnswer.render(request, emptyList(), listOf(pr), buildJsonObject {})
        assertFalse(absent.contains(pr.name))
        val answer = GitHubCapabilityAnswer.render(request, listOf(pr), emptyList(), buildJsonObject {})
        assertTrue(answer.contains(pr.name))
        assertTrue(answer.contains("headers"))
        assertFalse(answer.contains("merge"))
        val discovered = GitHubCapabilityAnswer.render(request, emptyList(), listOf(pr), buildJsonObject { put("observed_names", JsonArray(listOf(JsonPrimitive(pr.name)))) })
        assertTrue(discovered.contains("In discovery catalog"))
        val changed = buildJsonObject {
            put("observed_names", JsonArray(listOf(JsonPrimitive(pr.name))))
            put("contract_changes", JsonArray(listOf(buildJsonObject { put("name", pr.name) })))
        }
        assertFalse(pr.name in GitHubCapabilityAnswer.render(request, emptyList(), listOf(pr), changed))
        assertTrue(pr.name in GitHubCapabilityAnswer.render(request, listOf(pr), listOf(pr), changed))
    }

    @Test fun singleOperationOfficialPullRequestSchemasParticipateOnlyInMatchingAnswers() {
        fun remote(name: String) = AgentToolDefinition("mcp__work__$name", name, buildJsonObject {}, capabilities = setOf(AgentToolCapability.GITHUB))
        val read = remote("get_pull_request")
        val list = remote("list_pull_requests")
        val merge = remote("merge_pull_request")
        val lookalike = read.copy(name = "mcp__unknown__get_pull_request", capabilities = emptySet())
        val unknown = remote("new_action")
        val issue = remote("issue_read")
        val tools = listOf(read, list, merge, lookalike, unknown, issue)
        val stale = buildJsonObject { put("observed_names", JsonArray(listOf(JsonPrimitive("mcp__revoked__get_pull_request")))) }
        fun answer(text: String) = GitHubCapabilityAnswer.render(requireNotNull(GitHubCapabilityRequest.from(text)), tools, emptyList(), stale)
        val inspection = answer("Can you inspect my PRs?")
        assertTrue(read.name in inspection && list.name in inspection)
        assertFalse(merge.name in inspection || lookalike.name in inspection)
        val operations = answer("List GitHub actions")
        assertTrue(read.name in operations && merge.name in operations)
        assertTrue(unknown.name in operations && issue.name in operations)
        assertFalse(lookalike.name in operations)
        val filtered = answer("List GitHub actions matching list_pull_requests")
        assertTrue(list.name in filtered && read.name !in filtered)
        assertFalse("mcp__revoked" in answer("List tools"))
    }

    @Test fun catalogPagesAndSearchExposeEveryMatchingTool() {
        val tools = (1..37).map { AgentToolDefinition("item_" + it.toString().padStart(2, '0'), "Example", buildJsonObject {}) }
        fun answer(text: String) = GitHubCapabilityAnswer.render(requireNotNull(GitHubCapabilityRequest.from(text)), tools, emptyList(), buildJsonObject {})
        val first = answer("list tools")
        assertTrue("Page 1 of 3" in first && "list tools page 2" in first)
        val all = first + answer("list tools page 2") + answer("list tools page 3")
        for (tool in tools) assertTrue(tool.name, tool.name in all)
        val match = answer("list tools matching item_36")
        assertTrue("item_36" in match && "item_35" !in match)
        val features = answer("list features matching voice")
        assertTrue("Documented" in features && "voice" in features.lowercase())
    }
}
