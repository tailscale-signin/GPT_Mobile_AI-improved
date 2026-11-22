package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
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

    @Test fun deadlineControlTargetsOneOwnedWaitAndDropsItsNoticeWhenTheFenceChanges() {
        val original = AgentRun("run", 13, 2, 3, "profile", "provider", "model", AgentRunStatus.WAITING_PROVIDER_LIMIT, ownershipFence = 7)
        var run by mutableStateOf(original)
        val evidence = GitHubWorkflowEvidence(
            providerWaits = mapOf("run" to ProviderWaitDeadline("run", 7, 30_000, 120_000)),
            prerequisiteNoticeRunId = "run",
            prerequisiteNoticeFence = 7,
            prerequisiteNotice = "Saved wait scheduled"
        )
        val scheduled = mutableListOf<AgentRun>()
        val recoveries = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent {
            val state = GitHubProgrammerState(chatId = 13, runs = listOf(run), eventCountsByRun = mapOf("run" to 0))
            MaterialTheme {
                // Match the scrollable Task details host before tapping the control.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GitHubWorkflowExecutionContent(state, evidence, recoveries::add, {}, onRetryProvider = scheduled::add)
                }
            }
        }
        compose.onNodeWithText("Earliest provider retry: 1970-01-01T00:02:00Z").assertExists()
        compose.onNodeWithText("Retry after the provider deadline").assertIsEnabled().performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(original), scheduled)
            assertEquals(emptyList<GitHubRecoveryRequest>(), recoveries)
            run = original.copy(ownershipFence = 8)
        }
        compose.onNodeWithText("Retry after the provider deadline").assertDoesNotExist()
        compose.onNodeWithText("Saved wait scheduled").assertDoesNotExist()
    }

    @Test fun changedAuthorityControlOnlyRequestsItsSavedOwnerSettingsRoute() {
        val run = AgentRun("run", 13, 2, 3, "saved-profile", "provider", "model", AgentRunStatus.BLOCKED, terminalError = "DISPATCH_POLICY_CHANGED", ownershipFence = 7)
        val proof = Json.parseToJsonElement("""{"task_outcome":"CONFIGURATION_INVALIDATED","original_failure":{"code":"DISPATCH_POLICY_CHANGED"}}""") as JsonObject
        val state = GitHubProgrammerState(chatId = 13, runs = listOf(run), eventCountsByRun = mapOf("run" to 0))
        val reviews = mutableListOf<AgentRun>()
        val recoveries = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent {
            MaterialTheme { GitHubWorkflowExecutionContent(state, GitHubWorkflowEvidence(mapOf("run" to proof)), recoveries::add, {}, onRefreshAuthority = reviews::add) }
        }
        compose.runOnIdle { assertEquals(emptyList<AgentRun>(), reviews) }
        compose.onNodeWithText("Refresh changed authority").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(run), reviews)
            assertEquals(emptyList<GitHubRecoveryRequest>(), recoveries)
        }
    }

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
