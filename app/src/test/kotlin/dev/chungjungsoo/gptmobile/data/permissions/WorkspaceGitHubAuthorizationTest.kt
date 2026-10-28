package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubAuthorizationTest {
    private val dao = mockk<ToolApprovalDao>(relaxed = true)
    private val runs = mockk<AgentRunDao>()
    private val database = mockk<ChatDatabaseV2> {
        every { toolApprovalDao() } returns dao
        every { agentRunDao() } returns runs
    }
    private val connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "BEARER", null, null, toolPolicy = "READ_ONLY")
    private val connections = mockk<ToolConnectionRepository> { coEvery { getConnection("github") } returns connection }
    private val settings = mockk<SettingRepository>()
    private val state = MutableStateFlow<ToolApproval?>(null)
    private val context = GitHubConversationContext("github", "owner", "repo", "working", "a".repeat(40), "profile", autoApproveTools = true)
    private val manager = ToolApprovalManager(database, connections, settings = settings)

    private fun configure(workspace: GitHubConversationContext = context, previous: String? = null) {
        coEvery { runs.getById("run") } returns AgentRun("run", 7, 1, 2, "profile", "provider", "model")
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(conversationGitHub = mapOf(7 to workspace))
        coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } returns previous
        coEvery { dao.insert(any()) } coAnswers {
            state.value = firstArg()
            1L
        }
        every { dao.observe(any()) } returns state
        coEvery { dao.claim(any()) } returns 1
    }

    @Test fun workspaceRepositoryToolsRunWithoutPerToolPrompts() = runTest {
        configure()
        for (action in listOf("create_branch", "commit_files", "create_pull_request", "repo_status")) {
            state.value = null
            assertTrue(manager.authorize("github", "run", action, "github", buildJsonObject { put("action", action) }))
            assertEquals("APPROVED", state.value?.state)
        }
    }

    @Test fun unrelatedRepositoryAndOrdinaryConversationRetainTheirPolicy() = runTest {
        configure()
        assertFalse(
            manager.authorize(
                "github",
                "run",
                "other",
                "github",
                buildJsonObject {
                    put("action", "create_branch")
                    put("owner", "someone-else")
                    put("repo", "repo")
                }
            )
        )
        configure(context.copy(autoApproveTools = false))
        assertFalse(manager.authorize("github", "run", "ordinary", "github", buildJsonObject { put("action", "create_branch") }))
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun automaticPermissionNeverReplaysAnUncertainWrite() = runTest {
        configure(previous = "OUTCOME_UNKNOWN")
        assertFalse(manager.authorize("github", "run", "repeat", "github", buildJsonObject { put("action", "create_branch") }))
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
