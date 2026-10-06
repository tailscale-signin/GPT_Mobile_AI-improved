package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationGitHubPolicyTest {
    @Test
    fun `short follow-ups retain repository intent while new public questions do not`() {
        val prior = listOf("Fix github.com/owner/repo", "Continue")
        assertTrue(isGitHubTask(repositoryRoutingTask("Try again", prior)))
        assertFalse(isGitHubTask(repositoryRoutingTask("What is the weather in London?", prior)))
        assertFalse(isGitHubTask(repositoryRoutingTask("Continue", listOf("Find a restaurant"))))
    }

    @Test
    fun `local first keeps aggregate web search and GitHub available for primary recovery`() {
        val delegate = tool("delegate_to_model")
        val aggregateWeb = tool("web_search")
        val readUrl = tool("read_url")
        val remoteReadUrl = tool("mcp__reader__read_url", "read_url", connectionUid = "remote-reader")
        val directEngine = tool("mcp__brave__web_search", "brave_web_search")
        val extraReader = tool("mcp__firecrawl__scrape", "firecrawl_scrape")
        val githubNative = tool("github__work", "github")
        val githubMcp = tool("mcp__github__create_pull_request")
        val calculator = tool("calculate_expression")
        val tools = listOf(delegate, aggregateWeb, readUrl, remoteReadUrl, directEngine, extraReader, githubNative, githubMcp, calculator)

        assertEquals(
            listOf(delegate, aggregateWeb, readUrl, githubNative, githubMcp, calculator),
            primaryDelegationTools(tools, true, 25)
        )
        assertEquals(tools, primaryDelegationTools(tools, false, 0))
        assertEquals(
            listOf(delegate, aggregateWeb, readUrl, githubNative, githubMcp, calculator),
            primaryDelegationTools(tools, true, 100)
        )
    }

    @Test
    fun `partial delegation retains authorized location and other primary tools`() {
        val location = tool("device_location")
        val remoteLocation = tool("mcp__phone__get_location", "get_location", connectionUid = "phone")
        val calculator = tool("calculate_expression")
        val memory = tool("memory")
        val tools = listOf(location, remoteLocation, calculator, memory)
        listOf(25, 35, 65, 100).forEach { ownership ->
            assertEquals(tools, primaryDelegationTools(tools, true, ownership))
        }
    }

    @Test
    fun `maximum delegation exposes only the helper before the final pass`() {
        val delegate = tool("delegate_to_model")
        val tools = listOf(delegate, tool("github"), tool("web_search"), tool("device_location"), tool("calculate_expression"))
        assertEquals(listOf(delegate), primaryDelegationTools(tools, true, 0))
        assertEquals(tools, primaryDelegationTools(tools, false, 0))
    }

    @Test
    fun `combined synthesis cannot recursively expose delegation`() {
        val delegate = tool("delegate_to_model")
        val search = tool("web_search")
        val read = tool("read_url")
        val tools = listOf(delegate, search, read)

        assertEquals(listOf(search, read), synthesisSafeTools(tools, "combined-synthesis:run-1"))
        assertEquals(tools, synthesisSafeTools(tools, "normal-run"))
    }

    @Test
    fun `repository actions bypass public web planning`() {
        listOf("Create a draft PR", "Push a branch", "Inspect the repository", "Read github.com/owner/project", "Commit files").forEach {
            assertTrue(it, isGitHubTask(it))
        }
        assertFalse(isGitHubTask("Find restaurants near me"))
    }

    @Test
    fun `GitHub tasks suppress POSIX shell fallback when native GitHub is present`() {
        val github = tool("github__work", "github")
        val posix = tool("mcp__posix__api_run", "api:run", connectionName = "posix:default")
        val calculator = tool("calculate_expression")

        val routed = preferNativeGitHubForTask(
            listOf(posix, calculator, github),
            "Create a branch and commit the fix in the GitHub repository"
        )

        assertEquals(listOf(calculator, github), routed)
        assertFalse(routed.any { it.isShellExecutionTool() })
        assertEquals(listOf(posix, calculator), preferNativeGitHubForTask(listOf(posix, calculator, github), "Calculate 2 + 2"))
    }

    @Test
    fun `capability refusal is scoped to helper and does not reject successful writes`() {
        val task = "Create a draft PR in the repo"
        assertTrue(gitHubCapabilityRefusal(task, "I cannot do it because **no GitHub integration, Git CLI, shell, or remote repository write tools are enabled**."))
        assertFalse(gitHubCapabilityRefusal(task, "Created the GitHub draft PR successfully."))
        assertFalse(gitHubCapabilityRefusal("Explain this error message", "No GitHub integration is enabled."))
    }

    @Test fun `maximum delegation final pass has no tools even when follow ups are enabled`() {
        val tools = listOf(tool("delegate_to_model"), tool("web_search"), tool("read_url"), tool("github"), tool("device_location"))
        assertTrue(reviewedSynthesisTools(tools, 0, false).isEmpty())
        assertTrue(reviewedSynthesisTools(tools, 0, true).isEmpty())
        assertEquals(listOf(tools[3], tools[4]), reviewedSynthesisTools(tools, 50, false))
    }

    private fun tool(
        name: String,
        realName: String = name,
        connectionName: String? = null,
        connectionUid: String? = null
    ): ResolvedAgentTool {
        val agent = object : AgentTool {
            override val definition = AgentToolDefinition(name, "Test tool", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("ok"), false)
        }
        return ResolvedAgentTool(agent, connectionUid, connectionName, realName, name)
    }
}
