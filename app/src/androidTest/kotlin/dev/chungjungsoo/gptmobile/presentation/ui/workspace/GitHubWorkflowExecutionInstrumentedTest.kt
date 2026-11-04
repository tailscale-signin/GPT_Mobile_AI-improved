package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** Renders the same content as the Hilt-backed Status panel, including its separate details window. */
class GitHubWorkflowExecutionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedAcceptanceStatesRemainDistinctFromSuccessfulOperations() {
        val run = run(AgentRunStatus.COMPLETED)
        val state = state(run, listOf(event(status = ToolEventStatus.COMPLETED)))
        var evidence by mutableStateOf(GitHubWorkflowEvidence())
        compose.setContent { Panel(state, evidence) }

        compose.onNodeWithText("Answer ready · Acceptance: Untested").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("read: 1 operations · 1 completed · 0 failed").performScrollTo().assertIsDisplayed()
        listOf("PASS" to "Verified", "FAIL" to "Failed", "BLOCKED" to "Blocked", "NOT_TESTED" to "Untested").forEach { (status, label) ->
            compose.runOnIdle { evidence = verification(run.runId, status) }
            compose.onNodeWithText("Answer ready · Acceptance: $label").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("$label: Requested regression").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun recoverableReadRoutesResumeAndRetryToSavedRun() {
        val run = run(AgentRunStatus.FAILED)
        val requests = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent { Panel(state(run, listOf(event(error = "SAVED_VIEW_UNAVAILABLE"))), onRecovery = requests::add) }

        compose.onNodeWithText("Resume").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Retry failed stage").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    GitHubRecoveryRequest(42, run.runId, TaskRecoveryAction.CONTINUE_RUN),
                    GitHubRecoveryRequest(42, run.runId, TaskRecoveryAction.RETRY_FAILED_STAGE)
                ),
                requests
            )
        }
    }

    @Test
    fun unknownMutationOffersReconciliationAndIncidentDetailsWithoutRetry() {
        val run = run(AgentRunStatus.FAILED)
        val requests = mutableListOf<GitHubRecoveryRequest>()
        var exports = 0
        val write = event(name = "github_write", arguments = """{"action":"commit"}""", error = "MUTATION_OUTCOME_UNKNOWN")
        compose.setContent { Panel(state(run, listOf(write)), onRecovery = requests::add, onExport = { exports++ }) }

        compose.onNodeWithText("Retry failed stage").assertDoesNotExist()
        compose.onNodeWithText("Resume").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(GitHubRecoveryRequest(42, run.runId, TaskRecoveryAction.CONTINUE_RUN)), requests) }
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("Workflow details").assertIsDisplayed()
        compose.onNodeWithText("Failure category: MUTATION_OUTCOME_UNKNOWN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved verification: not available\nEvidence retention: not confirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Run: ${run.runId}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export incident").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        compose.onNodeWithText("Workflow details").assertDoesNotExist()
    }

    @Test
    fun unknownGatewayOutcomeKeepsDetailsWithoutResumeOrRetry() {
        val run = run(AgentRunStatus.BLOCKED).copy(terminalError = "GATEWAY_OUTCOME_UNKNOWN")
        val requests = mutableListOf<GitHubRecoveryRequest>()
        compose.setContent { Panel(state(run), onRecovery = requests::add) }

        compose.onNodeWithText("Blocked · Acceptance: Blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("Retry failed stage").assertDoesNotExist()
        compose.onNodeWithText("The gateway outcome is unknown and no saved job can be reconciled.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("Failure category: GATEWAY_OUTCOME_UNKNOWN").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<GitHubRecoveryRequest>(), requests) }
    }

    @Test
    fun authorizationBlockKeepsDetailsWithoutRecovery() {
        val run = run(AgentRunStatus.BLOCKED).copy(terminalError = "AUTH_REQUIRED")
        compose.setContent { Panel(state(run)) }

        compose.onNodeWithText("Blocked · Acceptance: Blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("Retry failed stage").assertDoesNotExist()
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("Failure category: AUTH_REQUIRED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Workflow details").assertDoesNotExist()
    }

    @Test
    fun submittingRequestDisablesRecoveryButPreservesDetails() {
        val run = run(AgentRunStatus.FAILED)
        compose.setContent { Panel(state(run, listOf(event(error = "SAVED_VIEW_UNAVAILABLE"))).copy(submitting = true)) }

        compose.onNodeWithText("Resume").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry failed stage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("View details").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Workflow details").assertIsDisplayed()
    }

    @Composable
    private fun Panel(
        state: GitHubProgrammerState,
        evidence: GitHubWorkflowEvidence = GitHubWorkflowEvidence(),
        onRecovery: (GitHubRecoveryRequest) -> Unit = {},
        onExport: () -> Unit = {}
    ) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GitHubWorkflowExecutionContent(state, evidence, onRecovery, onExport)
            }
        }
    }

    private fun run(status: String) = AgentRun("saved-run", 42, 7, 8, "profile", "provider", "model", status)

    private fun state(run: AgentRun, events: List<ToolEvent> = emptyList()) = GitHubProgrammerState(
        chatId = 42,
        runs = listOf(run),
        events = events,
        eventCount = events.size,
        eventCountsByRun = events.groupingBy { it.runId }.eachCount()
    )

    private fun event(name: String = "github_read", arguments: String = "{}", status: String = ToolEventStatus.FAILED, error: String? = null) =
        ToolEvent("event", "saved-run", 1, "call", null, null, name, name, arguments, null, null, status, status == ToolEventStatus.FAILED, error = error)

    private fun verification(runId: String, status: String): GitHubWorkflowEvidence {
        val completed = status == "PASS"
        val value = Json.parseToJsonElement(
            """{"completion_verified":$completed,"evidence_retention_complete":true,"acceptance_ledger":{"completion_verified":$completed,"requirements":[{"criterion":"Requested regression","state":"$status","reason":"Saved test evidence"}]}}"""
        ) as JsonObject
        return GitHubWorkflowEvidence(verifications = mapOf(runId to value))
    }
}
