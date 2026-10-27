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

    @Test fun approvedReadRemainsAvailableWithoutMutationConfirmation() = runTest {
        coEvery { connections.getConnection("github") } returns connection.copy(approvedReadTools = "github")
        assertTrue(manager.authorize("github", "run", "call", "github", buildJsonObject { put("action", "get_file_contents") }))
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
