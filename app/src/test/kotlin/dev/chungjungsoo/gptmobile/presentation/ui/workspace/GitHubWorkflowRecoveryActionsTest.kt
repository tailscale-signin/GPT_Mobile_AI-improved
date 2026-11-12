package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GitHubProgrammerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubWorkflowRecoveryActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reconciliationWaitsForTheActualApprovalCardAndNeverRunsAutomaticallyWhenItResolves() {
        val run = AgentRun("run", 13, 2, 3, "profile", "provider", "model", AgentRunStatus.BLOCKED, terminalError = "OUTPUT_CONTINUATION_PENDING_TOOLS")
        val state = GitHubProgrammerState(chatId = 13, runs = listOf(run), eventCountsByRun = mapOf("run" to 0))
        var approvalPending by mutableStateOf(true)
        val actions = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent {
            MaterialTheme {
                GitHubWorkflowExecutionContent(state, GitHubWorkflowEvidence(), actions::add, {}, approvalPending)
            }
        }
        compose.onNodeWithText("Reconcile saved results").assertIsNotEnabled()
        compose.runOnIdle { approvalPending = false }
        compose.onNodeWithText("Reconcile saved results").assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<GitHubRecoveryRequest>(), actions) }
        compose.onNodeWithText("Reconcile saved results").performClick()
        compose.runOnIdle { assertEquals(listOf(GitHubRecoveryRequest(13, "run", TaskRecoveryAction.CONTINUE_RUN)), actions) }
    }

    @Test fun restoreSavedReadUsesTheOriginalRunAndNeverOffersFailedStageRetry() {
        val run = AgentRun("run", 13, 2, 3, "profile", "provider", "model", AgentRunStatus.FAILED, terminalError = "SAVED_VIEW_UNAVAILABLE")
        val failed = ToolEvent("event", "run", 0, "call", "github", "GitHub", "github_read", "github_read", """{"view_cursor":"saved:page:2"}""", null, null, ToolEventStatus.FAILED, true, error = "SAVED_VIEW_UNAVAILABLE")
        val proof = Json.parseToJsonElement("""{"evidence_retention_complete":true,"source_read_recovery":{"schema":"github.saved-read-recovery.v1","available":true,"read_only":true,"source_bound":true,"failed_call_id":"call","tool_name":"github_read","arguments":{"path":"A.kt","commit_sha":"${"a".repeat(40)}"},"source_receipt_id":"source","binding":"bound-task"}}""") as JsonObject
        val state = GitHubProgrammerState(chatId = 13, runs = listOf(run), events = listOf(failed), eventCountsByRun = mapOf("run" to 1))
        val actions = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent {
            MaterialTheme { GitHubWorkflowExecutionContent(state, GitHubWorkflowEvidence(mapOf("run" to proof)), actions::add, {}) }
        }
        compose.onNodeWithText("Retry failed stage").assertDoesNotExist()
        compose.onNodeWithText("Restore saved read").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(GitHubRecoveryRequest(13, "run", TaskRecoveryAction.CONTINUE_RUN)), actions) }
    }
}
