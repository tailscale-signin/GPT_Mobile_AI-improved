package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
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

class GitHubBoundRepositoryToolTest {
    private val selected = GitHubRepositoryContext("selected", "repository", "main")

    private class RecordingTool(name: String) :
        PreparableAgentTool,
        OwnedAgentTool {
        override val definition = AgentToolDefinition(name, "Native authorized operation", buildJsonObject { put("type", "object") })
        override val managesExecutionBudget = true
        override val coordinatesChildWork = true
        override val executionOwner = AgentToolExecutionOwner.CLIENT
        val executed = mutableListOf<JsonObject>()
        var prepared = 0
        var childBindings = 0
        override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool {
            childBindings++
            return this
        }
        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            prepared++
            return { execute(callId, arguments) }
        }
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            executed += arguments
            return AgentToolResult(callId, ToolResultContent.Json(arguments), isError = false)
        }
    }

    @Test fun explicitForeignRepositoryAndUrlAliasesCannotDispatchOrPrepare() = runTest {
        for ((name, action) in listOf("github__account" to "resolve_context", "github__account" to "repo_status", "github__account" to "update_file", "github_context__account" to "resolve", "github_write__account" to "commit", "github_pr__account" to "merge")) {
            for (target in listOf("other/repository", "https://github.com/other/repository.git")) {
                val delegate = RecordingTool(name)
                val bound = GitHubBoundRepositoryTool(delegate, selected)
                val arguments = buildJsonObject {
                    put("action", action)
                    put("repo", target)
                }
                val direct = bound.execute("direct", arguments)
                val prepared = bound.prepareExecution("prepared", arguments).invoke()
                for (result in listOf(direct, prepared)) {
                    assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", result.errorCode)
                    assertFalse(result.dispatched)
                    assertTrue(result.isError)
                }
                assertEquals(0, delegate.prepared)
                assertTrue(delegate.executed.isEmpty())
            }
        }
    }

    @Test fun omittedAndNormalizedTargetsPinTheBoundRepositoryWithoutChangingBranchPaths() = runTest {
        val delegate = RecordingTool("github_context__account")
        val bound = GitHubBoundRepositoryTool(delegate, selected)
        for (extra in listOf(emptyMap(), mapOf("repo" to JsonPrimitive("https://github.com/SELECTED/REPOSITORY.git")), mapOf("owner" to JsonPrimitive("selected")), mapOf("repo" to JsonPrimitive("repository")))) {
            val arguments = JsonObject(extra + mapOf("action" to JsonPrimitive("resolve"), "ref" to JsonPrimitive("fix/foo"), "path" to JsonPrimitive("other/repository/file.kt")))
            val result = bound.execute("read", arguments)
            assertFalse(result.isError)
            val executed = delegate.executed.last()
            assertEquals(JsonPrimitive("selected"), executed["owner"])
            assertEquals(JsonPrimitive("repository"), executed["repo"])
            assertEquals(arguments["ref"], executed["ref"])
            assertEquals(arguments["path"], executed["path"])
            assertEquals(arguments["action"], executed["action"])
            assertEquals(executed, result.executionReceipt?.get("corrected_arguments"))
        }
    }

    @Test fun accountDiscoveryCannotRetargetTheFollowingRepositoryOperation() = runTest {
        val delegate = RecordingTool("github_context__account")
        val bound = GitHubBoundRepositoryTool(delegate, selected)
        val discovery = buildJsonObject {
            put("action", "repositories")
            put("query", "other/repository")
        }
        assertFalse(bound.execute("discover", discovery).isError)
        assertEquals(discovery, delegate.executed.single())
        assertEquals(
            "GITHUB_REPOSITORY_TARGET_MISMATCH",
            bound.execute(
                "retarget",
                buildJsonObject {
                    put("action", "resolve")
                    put("repo", "other/repository")
                }
            ).errorCode
        )
        bound.execute("selected", buildJsonObject { put("action", "status") })
        assertEquals(2, delegate.executed.size)
        assertEquals(JsonPrimitive("selected"), delegate.executed.last()["owner"])
        assertEquals(JsonPrimitive("repository"), delegate.executed.last()["repo"])
    }

    @Test fun wrongConnectionBlocksRepositoryWorkWhilePreservingAccountDiscovery() = runTest {
        val delegate = RecordingTool("github__other_account")
        val bound = GitHubBoundRepositoryTool(delegate, selected, connectionMatches = false)
        assertFalse(bound.execute("list", buildJsonObject { put("action", "list_repositories") }).isError)
        val denied = bound.execute("write", buildJsonObject { put("action", "update_file") })
        assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", denied.errorCode)
        assertFalse(denied.dispatched)
        assertEquals(1, delegate.executed.size)
    }

    @Test fun cursorOnlyFacadesStayExactButRawActionsCannotUseCursorAsScopeBypass() = runTest {
        val compact = RecordingTool("github_read__account")
        val savedView = buildJsonObject { put("view_cursor", "authorized:fragment") }
        GitHubBoundRepositoryTool(compact, selected).execute("view", savedView)
        assertEquals(savedView, compact.executed.single())
        val raw = RecordingTool("github__account")
        val denied = GitHubBoundRepositoryTool(raw, selected).execute(
            "retarget",
            buildJsonObject {
                put("action", "resolve_context")
                put("repo", "other/repository")
                put("view_cursor", "not-an-escape")
            }
        )
        assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", denied.errorCode)
        assertTrue(raw.executed.isEmpty())
    }

    @Test fun malformedTargetsFailBeforePreparationAndDelegationContractsRemainIntact() = runTest {
        val delegate = RecordingTool("github__account")
        val bound = GitHubBoundRepositoryTool(delegate, selected)
        for (repo in listOf("https://github.com/selected/repository?redirect=other", "https://elsewhere.test/selected/repository", "selected/repository/extra")) {
            val result = bound.prepareExecution(
                "invalid",
                buildJsonObject {
                    put("action", "repo_status")
                    put("repo", repo)
                }
            ).invoke()
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertEquals(0, delegate.prepared)
        assertTrue(delegate.executed.isEmpty())
        assertSame(delegate.definition, bound.definition)
        assertTrue(bound.managesExecutionBudget)
        assertTrue(bound.coordinatesChildWork)
        assertEquals(delegate.executionOwner, bound.executionOwner)
        val rebound = bound.withChildBudget { it } as PreparableAgentTool
        assertEquals(1, delegate.childBindings)
        val prepared = rebound.prepareExecution("allowed", buildJsonObject { put("action", "repo_status") }).invoke()
        assertEquals(1, delegate.prepared)
        assertEquals(JsonPrimitive("selected"), delegate.executed.single()["owner"])
        assertEquals(delegate.executed.single(), prepared.executionReceipt?.get("corrected_arguments"))
        val child = RecordingTool("github_write__account")
        val coordinator = object : AgentTool {
            override val definition = delegate.definition
            override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = bind(child)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Use the bound child")
        }
        val guardedChild = GitHubBoundRepositoryTool(coordinator, selected).withChildBudget { it }
        val denied = guardedChild.execute(
            "child",
            buildJsonObject {
                put("action", "commit")
                put("repo", "other/repository")
            }
        )
        assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", denied.errorCode)
        assertFalse(denied.dispatched)
        assertTrue(child.executed.isEmpty())
    }
}
