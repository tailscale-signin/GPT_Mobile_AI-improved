package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubProviderPolicyTest {
    private val official = connection("official", "https://api.githubcopilot.com/mcp")
    private val pc = connection("pc", "http://127.0.0.1:8765/mcp")

    private fun connection(uid: String, url: String) = ToolConnection(uid, uid, uid, ToolConnectionType.MCP, url, "BEARER", "secret", null)
    private fun tool(name: String, uid: String?, native: Boolean = false, authenticated: Boolean = false): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(name, "Fixture", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("read receipt"), false)
        }
        return ResolvedAgentTool(tool, uid, uid, name, "${uid.orEmpty()}__$name", nativeGitHub = native, nativeGitHubAuthenticated = authenticated)
    }

    @Test fun authenticatedNativeWinsEquivalentOperationsBothInitiallyAndAfterColdDiscoveryWhilePcBindingsRemain() {
        val native = tool("github_read", "native", native = true, authenticated = true)
        val remote = tool("get_file_contents", "official")
        val exactPcBinding = tool("get_file_contents", "pc")
        val unrelated = tool("get_issue", "official")
        assertEquals(listOf(native, exactPcBinding, unrelated), GitHubProviderPolicy.select(listOf(native, remote, exactPcBinding, unrelated), emptyList(), listOf(official, pc)))
        assertEquals(listOf(exactPcBinding, unrelated), GitHubProviderPolicy.select(listOf(remote, exactPcBinding, unrelated), listOf(native), listOf(official, pc)))
    }

    @Test fun mcpOnlyAndAnonymousNativeFallbackPreserveAuthorizedRemoteOperationsInitiallyAndOnColdAdditions() {
        val anonymous = tool("github_read", null, native = true)
        val remote = tool("get_file_contents", "official")
        val search = tool("search_code", "official")
        assertEquals(listOf(remote, search), GitHubProviderPolicy.select(listOf(anonymous, remote, search), emptyList(), listOf(official)))
        assertEquals(listOf(remote, search), GitHubProviderPolicy.select(listOf(remote, search), listOf(anonymous), listOf(official)))
        assertEquals(listOf(remote), GitHubProviderPolicy.select(listOf(remote), emptyList(), listOf(official)))
        assertEquals(listOf(anonymous), GitHubProviderPolicy.select(listOf(anonymous), emptyList(), listOf(official)))
    }
}
