package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.RequiredToolReadinessFailure
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun githubModePreservesAllAuthorizedProvidersInitiallyAndAfterColdDiscovery() {
        val native = tool("github_read", "native", native = true, authenticated = true)
        val remote = tool("get_file_contents", "official")
        val exactPcBinding = tool("get_file_contents", "pc")
        assertEquals(listOf(native, remote, exactPcBinding), GitHubProviderPolicy.select(listOf(native, remote, exactPcBinding), emptyList(), listOf(official, pc), githubMode = true))
        assertEquals(listOf(remote, exactPcBinding), GitHubProviderPolicy.select(listOf(remote, exactPcBinding), listOf(native), listOf(official, pc), githubMode = true))
    }

    @Test fun officialEndpointLabelsSanitizedSchemasWithoutTrustingAliasOrDescription() {
        val schema = AgentToolDefinition(namespaceMcpToolName("Work connection with a very long private label", "get_file_contents"), "GitHub reader", buildJsonObject {})
        assertFalse(schema.name.contains("github", ignoreCase = true))
        assertTrue(AgentToolCapability.GITHUB in GitHubProviderPolicy.definition(official, schema).capabilities)
        listOf(pc, official.copy(endpointUrl = "https://api.githubcopilot.com.attacker.example/mcp"), official.copy(endpointUrl = "https://other.example/api.githubcopilot.com/mcp")).forEach { other ->
            assertFalse(AgentToolCapability.GITHUB in GitHubProviderPolicy.definition(other, schema).capabilities)
        }
    }

    @Test fun fullGithubAcquisitionAwaitsCompleteSelectedSchemasAndLeavesOtherCatalogsProgressive() = runTest {
        val remote = Tool("get_file_contents", inputSchema = ToolSchema())
        var cachedReads = 0
        var discoveries = 0
        val loaded = GitHubProviderPolicy.acquireCatalog(official, setOf(remote.name), githubMode = true, cachedOnly = true, cached = {
            cachedReads++
            emptyList()
        }, discover = { await ->
            assertTrue(await)
            discoveries++
            listOf(remote)
        })
        assertEquals(listOf(remote), loaded)
        assertEquals(0, cachedReads)
        assertEquals(1, discoveries)
        for ((connection, mode) in listOf(official to false, pc to true)) {
            val cached = GitHubProviderPolicy.acquireCatalog(connection, setOf(remote.name), mode, cachedOnly = true, cached = {
                cachedReads++
                emptyList()
            }, discover = { error("Optional catalog must remain deferred") })
            assertTrue(cached.isEmpty())
        }
        assertEquals(2, cachedReads)
    }

    @Test fun fullGithubAcquisitionFailureOrMissingSelectedSchemaBlocksBeforeModelInsteadOfReturningPartialCatalog() = runTest {
        val failure = IllegalStateException("Connection could not load")
        val rejected = runCatching {
            GitHubProviderPolicy.acquireCatalog(official, setOf("get_file_contents"), true, true, cached = { emptyList() }, discover = { throw failure })
        }.exceptionOrNull()
        assertTrue(rejected is RequiredToolReadinessFailure)
        assertEquals("REQUIRED_TOOL_ACQUISITION_FAILED", (rejected as RequiredToolReadinessFailure).errorCode)
        assertSame(failure, rejected.cause)
        val missing = runCatching {
            GitHubProviderPolicy.acquireCatalog(official, setOf("get_file_contents", "search_code"), true, true, cached = { emptyList() }, discover = { listOf(Tool("search_code", inputSchema = ToolSchema())) })
        }.exceptionOrNull()
        assertTrue(missing is RequiredToolReadinessFailure)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", (missing as RequiredToolReadinessFailure).errorCode)
        assertEquals(namespaceMcpToolName(official.alias, "get_file_contents"), missing.toolName)
        val cancelled = CancellationException("Stop acquisition")
        assertSame(
            cancelled,
            runCatching {
                GitHubProviderPolicy.acquireCatalog(official, setOf("get_file_contents"), true, true, cached = { emptyList() }, discover = { throw cancelled })
            }.exceptionOrNull()
        )
    }
}
