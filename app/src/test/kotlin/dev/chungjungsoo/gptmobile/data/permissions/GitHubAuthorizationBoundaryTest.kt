package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubAuthorizationBoundaryTest {
    private val connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "BEARER", null, null, toolPolicy = "TRUSTED")
    private val dao = mockk<ToolApprovalDao>(relaxed = true)
    private val database = mockk<ChatDatabaseV2> { every { toolApprovalDao() } returns dao }
    private val connections = mockk<ToolConnectionRepository> { coEvery { getConnection("github") } returns connection }
    private val manager = ToolApprovalManager(database, connections)

    @Test fun trustedNativeReadCanRetryAfterPreviousUnknownOutcome() = runTest {
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns "OUTCOME_UNKNOWN"
        val state = MutableStateFlow<ToolApproval?>(null)
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        every { dao.observe(any()) } returns state
        coEvery { dao.claim(any()) } returns 1
        assertTrue(
            manager.authorizeOrThrow(
                "github",
                "run",
                "retry",
                "github_read__github_api",
                buildJsonObject {
                    put("path", "README.md")
                }
            )
        )
        assertEquals("APPROVED", state.value?.state)
        coVerify(exactly = 0) { dao.previousMatchingAction(any(), any(), any(), any(), any()) }
    }

    @Test fun failedReadReceiptDoesNotAppearAsAnUncertainMutation() = runTest {
        every { dao.observe("run:read") } returns MutableStateFlow(
            ToolApproval("run:read", "run", "GitHub", "github", "hash", argumentPreview = GitHubAuthorizationBoundary.READ_APPROVAL_PREFIX, state = "EXECUTING")
        )
        every { dao.observe("run:write") } returns MutableStateFlow(
            ToolApproval("run:write", "run", "GitHub", "github", "hash", state = "EXECUTING")
        )
        manager.finish("run", "read", false)
        manager.finish("run", "write", false)
        coVerify { dao.finish("run:read", "READ_FAILED") }
        coVerify { dao.finish("run:write", "OUTCOME_UNKNOWN") }
    }

    @Test fun unknownWriteIsStillBlockedEvenWithTrustedPermission() = runTest {
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns "OUTCOME_UNKNOWN"
        every { dao.observe(any()) } returns MutableStateFlow(null)
        val failure = runCatching {
            manager.authorizeOrThrow("github", "run", "retry", "github", buildJsonObject { put("action", "create_branch") })
        }.exceptionOrNull()
        assertEquals("DUPLICATE_ACTION", (failure as? ToolAuthorizationRejected)?.code)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun replayClassificationNeverIncludesMutationsOrUnknownTools() {
        for (action in listOf("commit_files", "create_branch", "stage_patch", "dispatch_workflow", "rerun_workflow_run", "delete_repository")) {
            assertFalse(action, GitHubAuthorizationBoundary.isReplayableRead("github", buildJsonObject { put("action", action) }))
        }
        assertFalse(GitHubAuthorizationBoundary.isReplayableRead("arbitrary_mcp_read", buildJsonObject {}))
        assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_search__github_api", buildJsonObject {}))
    }

    @Test fun graphReadClassificationSeparatesIndexingFromStatusAndQueries() {
        val reads = listOf("status", "query", "node", "callers", "callees", "path", "impact", "architecture", "diff", "diagnose", "contracts", "prs", "notes", "requirements", "wiki", "metrics")
        for (action in reads + listOf("index", "note", "federated", "provider")) {
            for ((tool, arguments) in graphForms(action)) {
                assertEquals("$tool $action", action in reads, GitHubAuthorizationBoundary.isReplayableRead(tool, arguments))
                assertEquals("$tool $action", action == "index", GitHubAuthorizationBoundary.isLocalGraphIndexing(tool, arguments))
            }
        }
        for (tool in listOf("repository_graph_extra", "other__repository_graph", "github_context", "github__alias")) {
            assertFalse(tool, GitHubAuthorizationBoundary.isLocalGraphIndexing(tool, buildJsonObject { put("action", "index") }))
        }
        assertFalse(GitHubAuthorizationBoundary.isLocalGraphIndexing("github", buildJsonObject { put("graph_action", "index") }))
        assertFalse(GitHubAuthorizationBoundary.isLocalGraphIndexing("repository_graph", buildJsonObject { put("action", true) }))
        assertFalse(
            GitHubAuthorizationBoundary.isLocalGraphIndexing(
                "repository_graph",
                buildJsonObject {
                    put("action", "status")
                    put("options", buildJsonObject { put("action", "index") })
                }
            )
        )
    }

    @Test fun localGraphIndexingKeepsItsExistingNativeReadOnlyGrantWithoutReplayAuthority() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "github")
        for (control in listOf(null, "start", "pause", "resume", "cancel")) {
            val options = buildJsonObject {
                if (control != null) {
                    put("background", true)
                    put("job_action", control)
                    put("job_id", "observed-job")
                    put("job_generation", 2)
                }
            }
            for ((tool, arguments) in graphForms("index", options)) {
                assertTrue(GitHubAuthorizationBoundary.isLocalGraphIndexing(tool, arguments))
                assertFalse(GitHubAuthorizationBoundary.isReplayableRead(tool, arguments))
                assertTrue(manager.authorize("github", "run", "$tool-$control", tool, arguments))
            }
        }
        for (action in listOf("status", "query")) {
            for ((tool, arguments) in graphForms(action)) {
                assertTrue(manager.authorize("github", "run", "$tool-$action", tool, arguments))
            }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun localGraphIndexingCannotInventAReadGrantOrRepeatAnUnknownTrustedAction() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "")
        for ((tool, arguments) in graphForms("index")) {
            assertFalse(manager.authorize("github", "run", "ungranted-$tool", tool, arguments))
        }
        coEvery { connections.getConnection("github") } returns connection
        val nativeHash = ScopedToolGrant.canonicalHash(
            buildJsonObject {
                put("action", "repository_graph")
                put("graph_action", "index")
            }
        )
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns null
        coEvery { dao.previousMatchingAction("run", "GitHub", "github", nativeHash, any()) } returns "OUTCOME_UNKNOWN"
        every { dao.observe(any()) } returns MutableStateFlow(null)
        for ((tool, arguments) in graphForms("index")) {
            val failure = runCatching { manager.authorizeOrThrow("github", "run", "retry-$tool", tool, arguments) }.exceptionOrNull()
            assertEquals("DUPLICATE_ACTION", (failure as? ToolAuthorizationRejected)?.code)
        }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 6) { dao.previousMatchingAction("run", "GitHub", "github", nativeHash, any()) }
    }

    private fun graphForms(action: String, options: JsonObject? = null): List<Pair<String, JsonObject>> {
        val arguments = buildJsonObject {
            put("action", action)
            options?.takeIf { it.isNotEmpty() }?.let { put("options", it) }
        }
        return listOf(
            "repository_graph" to arguments,
            "repository_graph__github_api" to arguments,
            "github" to JsonObject(arguments + mapOf("action" to JsonPrimitive("repository_graph"), "graph_action" to JsonPrimitive(action)))
        )
    }

    @Test fun immediatePatchPublicationCannotUseALocalStagingReadGrant() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "github")
        val local = buildJsonObject { put("action", "stage_patch") }
        assertTrue(GitHubAuthorizationBoundary.isLocalPatchStaging("github", local))
        assertTrue(manager.authorize("github", "run", "stage", "github", local))
        val immediate = buildJsonObject {
            put("action", "stage_patch")
            put("commit_immediately", true)
            put("branch", "fix/explicit")
            put("expected_head_sha", "a".repeat(40))
            put("message", "Publish exact patch")
        }
        assertFalse(GitHubAuthorizationBoundary.isLocalPatchStaging("github", immediate))
        assertFalse(manager.authorize("github", "run", "publish", "github", immediate))
        assertFalse(
            GitHubAuthorizationBoundary.isLocalPatchStaging(
                "github",
                buildJsonObject {
                    put("action", "stage_patch")
                    put("commit_immediately", "false")
                }
            )
        )
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun trustedImmediatePatchPublicationStillRequiresExactApproval() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(approvedReadTools = "github")
        val state = MutableStateFlow<ToolApproval?>(null)
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns null
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        every { dao.observe(any()) } returns state
        coEvery { dao.claim(any()) } returns 1
        val decision = async {
            manager.authorize(
                "github",
                "run",
                "publish",
                "github_stage_patch__github_api",
                buildJsonObject {
                    put("commit_immediately", true)
                    put("branch", "fix/explicit")
                    put("expected_head_sha", "a".repeat(40))
                    put("message", "Publish exact patch")
                    put("target_file", "notes.txt")
                    put("search_snippet", "old")
                    put("replacement_snippet", "new")
                }
            )
        }
        runCurrent()
        assertFalse(decision.isCompleted)
        val pending = requireNotNull(state.value)
        assertTrue(pending.requiresExactConfirmation)
        assertEquals("PENDING", pending.state)
        state.value = pending.copy(state = "APPROVED")
        assertTrue(decision.await())
    }

    @Test fun ordinaryTrustCannotAuthorizeAdministrativeChanges() = runTest {
        for (action in listOf("update_repository", "update_branch_protection", "add_repository_collaborator", "create_repository_secret", "update_repository_ruleset")) {
            val error = runCatching { manager.authorize("github", "run", "call", "github", buildJsonObject { put("action", action) }) }.exceptionOrNull()
            assertTrue("$action must require a separate elevated authorization", error is ToolAuthorizationRejected)
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun trustedWritesWaitForExactConfirmationAndCannotBecomePersistent() = runTest {
        val state = MutableStateFlow<ToolApproval?>(null)
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns null
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        every { dao.observe(any()) } returns state
        coEvery { dao.claim(any()) } returns 1
        val decision = async {
            manager.authorize(
                "github",
                "run",
                "call",
                "github",
                buildJsonObject {
                    put("action", "create_branch")
                    put("branch", "fix/reviewed")
                }
            )
        }
        runCurrent()
        assertFalse(decision.isCompleted)
        val pending = requireNotNull(state.value)
        assertEquals("PENDING", pending.state)
        assertTrue(pending.requiresExactConfirmation)
        manager.alwaysAllow(pending.id)
        manager.alwaysAllowProvider(pending.id)
        manager.allowInConversation(pending.id)
        coVerify(exactly = 0) { dao.decide(any(), any()) }
        state.value = pending.copy(state = "APPROVED")
        assertTrue(decision.await())
        coVerify(exactly = 1) { dao.claim(pending.id) }
    }

    @Test fun administrativeInspectionDoesNotGrantAdministrativeMutation() {
        assertFalse(GitHubAuthorizationBoundary.requiresElevatedAuthorization("get_branch_protection", buildJsonObject {}))
        assertTrue(GitHubAuthorizationBoundary.requiresElevatedAuthorization("repository_write", buildJsonObject { put("method", "update_repository_ruleset") }))
        assertTrue(GitHubAuthorizationBoundary.requiresElevatedAuthorization("get_repository_secret", buildJsonObject {}))
    }

    @Test fun branchSelectionRequiresExactApprovalDespiteTrustedReadGrants() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(approvedReadTools = "github")
        val state = MutableStateFlow<ToolApproval?>(null)
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        every { dao.observe(any()) } returns state
        coEvery { dao.claim(any()) } returns 1
        val decision = async {
            manager.authorize(
                "github",
                "run",
                "switch",
                "github_context__github_api",
                buildJsonObject {
                    put("action", "resolve")
                    put("ref", "fix/new")
                }
            )
        }
        runCurrent()
        assertFalse(decision.isCompleted)
        val pending = requireNotNull(state.value)
        assertTrue(pending.requiresExactConfirmation)
        assertEquals("PENDING", pending.state)
        manager.alwaysAllow(pending.id)
        coVerify(exactly = 0) { dao.decide(any(), any()) }
        state.value = pending.copy(state = "APPROVED")
        assertTrue(decision.await())
    }

    @Test fun immutableRevisionInspectionIsNotABranchSwitch() {
        assertFalse(GitHubAuthorizationBoundary.isBranchSelection("github_context", buildJsonObject { put("ref", "a".repeat(40)) }))
        assertFalse(GitHubAuthorizationBoundary.isBranchSelection("github_read", buildJsonObject { put("ref", "fix/new") }))
        assertFalse(GitHubAuthorizationBoundary.isBranchSelection("github_context", buildJsonObject {}))
    }

    @Test fun approvedReadRemainsAvailableWithoutMutationConfirmation() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(approvedReadTools = "github")
        assertTrue(manager.authorize("github", "run", "call", "github", buildJsonObject { put("action", "get_file_contents") }))
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun deniedIssueHistoryIsALocalPermissionFailureNotTokenExpiry() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "")
        every { dao.observe(any()) } returns MutableStateFlow(null)
        val failure = runCatching {
            manager.authorizeOrThrow(
                "github",
                "run",
                "history",
                "github_issue__github_api",
                buildJsonObject {
                    put("action", "history")
                    put("issue_number", 7)
                }
            )
        }.exceptionOrNull() as? ToolAuthorizationRejected
        assertEquals("PERMISSION_DENIED", failure?.code)
        assertTrue(failure?.message.orEmpty().contains("No GitHub request was sent"))
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun approvedIssueHistoryUsesTheExistingReadGrant() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "github")
        assertTrue(
            manager.authorizeOrThrow(
                "github",
                "run",
                "history",
                "github_issue__github_api",
                buildJsonObject {
                    put("action", "history")
                    put("issue_number", 7)
                }
            )
        )
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun removedGitHubConnectionCannotLookLikeExpiredRemoteCredentials() = runTest {
        coEvery { connections.getConnection("github") } returns null
        every { dao.observe(any()) } returns MutableStateFlow(null)
        val failure = runCatching {
            manager.authorizeOrThrow(
                "github",
                "run",
                "history",
                "github_issue__github_api",
                buildJsonObject {
                    put("action", "history")
                    put("issue_number", 7)
                }
            )
        }.exceptionOrNull() as? ToolAuthorizationRejected
        assertEquals("PERMISSION_REVOKED", failure?.code)
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
