package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMcpRepositoryToolTest {
    private val destination = GitHubRepositoryContext("my-org", "project", "main")

    @Test fun mismatchedMcpWriteIsUndispatchedBeforePreparationAndKeepsExactSchemaVisible() = runTest {
        val backend = Backend()
        val tool = GitHubMcpRepositoryTool(backend, destination, "push_files", readSources = setOf("upstream/project"))
        assertSame(backend.definition, tool.definition)
        val prepared = tool.prepareExecution("write", target("upstream", "project"))
        assertEquals(0, backend.prepared)
        val result = prepared()
        assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, backend.executed)
        assertSame(backend.definition, tool.definition)
    }

    @Test fun matchingDestinationPassesExactArgumentsAndExistingPreparation() = runTest {
        val backend = Backend()
        val tool = GitHubMcpRepositoryTool(backend, destination, "push_files")
        val args = target("my-org", "project")
        val prepared = tool.prepareExecution("write", args)
        assertEquals(1, backend.prepared)
        assertEquals(args, backend.arguments)
        assertEquals(0, backend.executed)
        assertFalse(prepared().isError)
        assertEquals(1, backend.executed)
    }

    @Test fun explicitlyAuthorizedReadSourceDoesNotBecomeNextWriteDestination() = runTest {
        val backend = Backend()
        val reader = GitHubMcpRepositoryTool(backend, destination, "get_file_contents", readSources = setOf("upstream/project"))
        val source = JsonObject(target("upstream", "project") + ("ref" to JsonPrimitive("source-sha")))
        assertFalse(reader.execute("read", source).isError)
        assertEquals(source, backend.arguments)
        val writer = GitHubMcpRepositoryTool(backend, destination, "push_files", readSources = setOf("upstream/project"))
        assertTrue(writer.execute("bad-write", source).isError)
        assertEquals(1, backend.executed)
        assertFalse(writer.execute("write", buildJsonObject {}).isError)
        assertEquals(target("my-org", "project"), backend.arguments)
    }

    @Test fun userApprovedCustomReadStillRequiresAnExplicitGrantedSourceAndKnownWritesCannotMasqueradeAsReads() = runTest {
        val backend = Backend()
        val reader = GitHubMcpRepositoryTool(backend, destination, "inspect_repository_document", approvedReadOnly = true, readSources = setOf("upstream/project"))
        assertFalse(reader.execute("read", target("upstream", "project")).isError)
        assertTrue(reader.execute("ungranted", target("other", "project")).isError)
        val writer = GitHubMcpRepositoryTool(backend, destination, "push_files", approvedReadOnly = true, readSources = setOf("upstream/project"))
        assertTrue(writer.execute("bad-write", target("upstream", "project")).isError)
        assertEquals(1, backend.executed)
    }

    @Test fun lifecycleAndOpaqueGlobalOperationsKeepTheirSeparateApprovalAndArguments() = runTest {
        val backend = Backend()
        val fork = GitHubMcpRepositoryTool(backend, destination, "fork_repository")
        val source = target("upstream", "project")
        assertFalse(fork.prepareExecution("fork", source)().isError)
        assertEquals(source, backend.arguments)
        assertEquals(1, backend.prepared)
        val global = Backend(ownerRepoSchema = false)
        val search = GitHubMcpRepositoryTool(global, destination, "search_code")
        val query = buildJsonObject { put("query", "repo:upstream/project implementation") }
        assertFalse(search.execute("search", query).isError)
        assertEquals(query, global.arguments)
        assertSame(global.definition, search.definition)
    }

    private fun target(owner: String, repo: String) = buildJsonObject {
        put("owner", owner)
        put("repo", repo)
    }

    private class Backend(ownerRepoSchema: Boolean = true) : PreparableAgentTool {
        var prepared = 0
        var executed = 0
        var arguments: JsonObject? = null
        override val definition = AgentToolDefinition(
            "mcp__arbitrary_alias__operation",
            "Official MCP operation",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        (if (ownerRepoSchema) listOf("owner", "repo") else listOf("query")).forEach { put(it, buildJsonObject { put("type", "string") }) }
                    }
                )
            },
            capabilities = setOf(AgentToolCapability.GITHUB)
        )
        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            prepared++
            this.arguments = arguments
            return { execute(callId, arguments) }
        }
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            executed++
            this.arguments = arguments
            return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("remote", true) }), false)
        }
    }
}
