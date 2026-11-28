package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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

    @Test fun copilotCloudWritesDefaultOffBeforePreparationOrDispatch() = runTest {
        for (operation in copilotWrites) {
            val backend = Backend()
            val tool = GitHubMcpRepositoryTool(backend, destination, operation, approvedReadOnly = true)
            assertTrue(GitHubMcpRepositoryTool.requiresCopilotConsent(operation))
            assertFalse(GitHubMcpRepositoryTool.isReadOnlyOperation(operation, approvedReadOnly = true))
            assertSame(backend.definition, tool.definition)
            val direct = tool.execute("direct-$operation", target("my-org", "project"))
            val prepared = tool.prepareExecution("prepared-$operation", target("my-org", "project"))()
            for (result in listOf(direct, prepared)) {
                assertEquals("GITHUB_COPILOT_DISABLED", result.errorCode)
                assertFalse(result.dispatched)
            }
            assertEquals(0, backend.prepared)
            assertEquals(0, backend.executed)
        }
    }

    @Test fun copilotDispatchRequiresLivePluginAndExactConversationConsent() = runTest {
        val context = GitHubConversationContext("selected-account", "my-org", "project", "main", "a".repeat(40), "profile")
        val backend = Backend()
        var features = AppFeatureSettings(
            pluginExecution = mapOf(ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = false)),
            conversationGitHub = mapOf(42 to context),
            conversationIdentities = mapOf(42 to "saved-chat"),
            githubCopilotConversations = mapOf(42 to GitHubCopilotConversationPreference("saved-chat", GitHubChatDecisions.workspaceKey(context), false))
        )
        val tool = GitHubMcpRepositoryTool(
            backend,
            destination,
            "create_pull_request_with_copilot",
            copilotAllowed = { GitHubCopilotAgentMergePolicy.enabled(features, 42, context) }
        )
        val args = JsonObject(
            target("my-org", "project") + mapOf(
                "problem_statement" to JsonPrimitive("Implement the authorized change."),
                "title" to JsonPrimitive("Implement the change"),
                "base_ref" to JsonPrimitive("feature/mobile")
            )
        )
        assertEquals("GITHUB_COPILOT_DISABLED", tool.execute("default-off", args).errorCode)
        features = features.copy(pluginExecution = mapOf(ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = true)))
        assertEquals("GITHUB_COPILOT_DISABLED", tool.execute("plugin-only", args).errorCode)
        features = features.copy(
            githubCopilotConversations = mapOf(42 to GitHubCopilotConversationPreference("saved-chat", GitHubChatDecisions.workspaceKey(context), true))
        )
        assertFalse(tool.execute("enabled", args).isError)
        assertEquals(args, backend.arguments)
        features = features.copy(conversationIdentities = mapOf(42 to "replacement-chat"))
        assertEquals("GITHUB_COPILOT_DISABLED", tool.execute("changed-chat", args).errorCode)
        assertEquals(1, backend.executed)
    }

    @Test fun officialCopilotResponseProducesImmediateNamedProgressWithoutInterpretingOpaqueOutput() = runTest {
        val context = GitHubConversationContext("selected-account", "my-org", "project", "main", "a".repeat(40), "profile")
        for (operation in copilotWrites + "get_copilot_job_status") {
            val backend = Backend()
            val tool = GitHubMcpRepositoryTool(backend, destination, operation, copilotAllowed = { true })
            val args = target("my-org", "project")
            val call = ProviderEvent.ToolCall("call-$operation", backend.definition.name, args)
            val result = tool.execute(call.callId, args).copy(content = ToolResultContent.Text("opaque provider prose: all tests passed and everything merged"))
            val receipt = result.executionReceipt?.get("github_copilot_request") as JsonObject
            assertEquals(JsonPrimitive(true), receipt["dispatch_observed"])
            assertEquals(JsonPrimitive(operation), receipt["operation"])
            for (progress in listOf(GitHubChatProgress("Repair the requested issue", context, "scope") { 0L }, GitHubChatProgress { 0L })) {
                progress.observeResult(call, result)
                progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId, toolArgs = args)))
                val text = progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().single().text
                assertTrue(text.contains("GitHub MCP connection returned a response"))
                assertTrue(text.contains("Copilot"))
                assertTrue(text.contains("remain unverified"))
                assertFalse(text.contains("all tests passed") || text.contains("everything merged"))
            }
            assertEquals(1, backend.executed)
        }
    }

    @Test fun genericIssueAssignmentRequiresLiveConsentAndCannotUseAReadGrant() = runTest {
        for ((operation, method) in listOf("create_issue" to null, "update_issue" to null, "issue_write" to "create", "issue_write" to "update")) {
            val backend = Backend()
            var allowed = false
            val tool = GitHubMcpRepositoryTool(backend, destination, operation, approvedReadOnly = true, readSources = setOf("upstream/project"), copilotAllowed = { allowed })
            val args = buildJsonObject {
                put("owner", "my-org")
                put("repo", "project")
                method?.let { put("method", it) }
                put("assignees", JsonArray(listOf(JsonPrimitive("copilot-swe-agent[bot]"))))
            }
            assertTrue(GitHubMcpRepositoryTool.requiresCopilotConsent(operation, args))
            assertFalse(GitHubMcpRepositoryTool.isReadOnlyOperation(operation, approvedReadOnly = true))
            assertEquals("GITHUB_COPILOT_DISABLED", tool.execute("off", args).errorCode)
            assertEquals(0, backend.executed)
            allowed = true
            assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", tool.execute("source", JsonObject(args + ("owner" to JsonPrimitive("upstream")))).errorCode)
            val prepared = tool.prepareExecution("prepared", args)
            assertEquals(1, backend.prepared)
            allowed = false
            assertEquals("GITHUB_COPILOT_DISABLED", prepared().errorCode)
            assertEquals(0, backend.executed)
            if (operation != "create_issue" && method != "create") {
                val cleanup = JsonObject(args + ("assignees" to JsonArray(emptyList())))
                assertFalse(GitHubMcpRepositoryTool.requiresCopilotConsent(operation, cleanup))
                assertFalse(tool.execute("remove-assignees", cleanup).isError)
                assertEquals(1, backend.executed)
            }
        }
    }

    @Test fun copilotConsentRevokedDuringExactApprovalPreventsPreparedDispatch() = runTest {
        val backend = Backend()
        var allowed = true
        val tool = GitHubMcpRepositoryTool(backend, destination, "request_copilot_review", copilotAllowed = { allowed })
        val prepared = tool.prepareExecution("review", JsonObject(target("my-org", "project") + ("pullNumber" to JsonPrimitive(7))))
        assertEquals(1, backend.prepared)
        allowed = false
        val result = prepared()
        assertEquals("GITHUB_COPILOT_DISABLED", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, backend.executed)
    }

    @Test fun copilotWritesCannotUseApprovedReadGrantToChangeSourceRepository() = runTest {
        for (operation in copilotWrites) {
            val backend = Backend()
            val tool = GitHubMcpRepositoryTool(backend, destination, operation, approvedReadOnly = true, readSources = setOf("upstream/project"), copilotAllowed = { true })
            val result = tool.prepareExecution("source-$operation", target("upstream", "project"))()
            assertEquals("GITHUB_REPOSITORY_TARGET_MISMATCH", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals(0, backend.prepared)
            assertEquals(0, backend.executed)
        }
    }

    @Test fun existingCopilotJobStatusRemainsReadOnlyWhenCloudWorkIsOff() = runTest {
        val backend = Backend()
        val tool = GitHubMcpRepositoryTool(
            backend,
            destination,
            "get_copilot_job_status",
            readSources = setOf("upstream/project"),
            copilotAllowed = { error("Status observation must not require starting new work.") }
        )
        assertTrue(GitHubMcpRepositoryTool.isReadOnlyOperation("get_copilot_job_status"))
        assertFalse(GitHubMcpRepositoryTool.requiresCopilotConsent("get_copilot_job_status"))
        assertFalse(GitHubMcpRepositoryTool.isReadOnlyOperation("get_job_status"))
        val args = target("upstream", "project")
        assertFalse(tool.prepareExecution("status", args)().isError)
        assertEquals(args, backend.arguments)
        assertEquals(1, backend.executed)
    }

    @Test fun copilotConsentFailureStaysUndispatchedAndCancellationPropagates() = runTest {
        val backend = Backend()
        val failedConsent = GitHubMcpRepositoryTool(backend, destination, "create_pull_request_with_copilot", copilotAllowed = { error("Settings unavailable") })
        val result = failedConsent.execute("unavailable", target("my-org", "project"))
        assertEquals("GITHUB_COPILOT_DISABLED", result.errorCode)
        assertFalse(result.dispatched)
        val cancellation = CancellationException("Stopped before dispatch")
        val cancelled = GitHubMcpRepositoryTool(backend, destination, "create_pull_request_with_copilot", copilotAllowed = { throw cancellation })
        try {
            cancelled.execute("cancelled", target("my-org", "project"))
            throw AssertionError("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertEquals(0, backend.executed)
    }

    @Test fun budgetRebindingPreservesCopilotConsentAndDispatchOwner() = runTest {
        for (owner in AgentToolExecutionOwner.entries) {
            val backend = Backend(executionOwner = owner)
            var allowed = true
            val tool = GitHubMcpRepositoryTool(backend, destination, "create_pull_request_with_copilot", copilotAllowed = { allowed })
                .withChildBudget { it }
            assertEquals(owner, (tool as OwnedAgentTool).executionOwner)
            assertFalse(tool.execute("enabled-$owner", target("my-org", "project")).isError)
            allowed = false
            assertEquals("GITHUB_COPILOT_DISABLED", tool.execute("revoked-$owner", target("my-org", "project")).errorCode)
            assertEquals(1, backend.executed)
        }
    }

    private fun target(owner: String, repo: String) = buildJsonObject {
        put("owner", owner)
        put("repo", repo)
    }

    private val copilotWrites = listOf("create_pull_request_with_copilot", "assign_copilot_to_issue", "assign_copilot_to_issue_with_intent", "request_copilot_review")

    private class Backend(
        ownerRepoSchema: Boolean = true,
        override val executionOwner: AgentToolExecutionOwner = AgentToolExecutionOwner.CLIENT
    ) : PreparableAgentTool,
        OwnedAgentTool {
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
