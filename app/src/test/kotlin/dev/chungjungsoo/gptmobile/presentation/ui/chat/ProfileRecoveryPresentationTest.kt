package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.agent.GitHubSchemaBudget
import dev.chungjungsoo.gptmobile.data.agent.GitHubToolContextBudgetException
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ReasoningCapabilityContent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ReasoningCapabilityUiState
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProfileRecoveryPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun schemaCapacityRecoveryShowsItsReasonAndOpensOnlyTheOwningSettings() {
        val event = GitHubToolContextBudgetException(2481, 1675, GitHubSchemaBudget(5632, 2485, 512, 256, 704)).providerFailure()
        val failure = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        val run = AgentRun("capacity-failure", 1, 1, 2, "small", "LLAMA", "selected-model", status = AgentRunStatus.FAILED, terminalError = event.message)
        val message = MessageV2(id = 2, chatId = 1, content = "", platformType = "small", currentRunId = run.runId, timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = failure)))
        val failures = combinedProfileFailures(1, "Inspect the repository", listOf(message), mapOf(run.runId to run), mapOf("small" to "Small-context profile"), emptyMap())
        val settings = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CombinedFailuresDialog(
                    failures = failures,
                    identities = emptyMap(),
                    onRetry = { _, _ -> error("retry must not dispatch") },
                    onRemove = { error("membership must not change") },
                    onContinue = { error("continuation must not start") },
                    onDismiss = {},
                    canContinue = false,
                    onChangeSettings = { settings += it },
                    onRecheckReasoning = { _, _ -> error("model qualification") },
                    onSwitchToAuto = { error("reasoning change") }
                )
            }
        }
        compose.onNodeWithText("Context too small for required tools").assertIsDisplayed()
        compose.onNodeWithText(failures.single().reason).assertIsDisplayed()
        compose.onNodeWithText("Change the rejected configuration before starting a new request. Saved replies and tool results remain.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry Small-context profile").assertDoesNotExist()
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
        compose.onNodeWithText("Retry with checked model").assertDoesNotExist()
        compose.onNodeWithText("Recheck model").assertDoesNotExist()
        compose.onNodeWithText("Change settings for Small-context profile").performClick()
        assertEquals(listOf("small"), settings)
    }

    @Test fun unsupportedOffExposesSeparateReadOnlyCheckAndExplicitAutoWithoutRetry() {
        var checks = 0
        var changes = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                ProfileRecoveryActions(
                    ProfileRecoveryKind.REASONING, "Llama", ReasoningRecoveryUiState(requested = ReasoningMode.OFF, status = ReasoningCapabilityStatus.UNSUPPORTED, frozenRequest = true), false,
                    { settings++ }, { error("billing") }, { checks++ }, { changes++ }, { error("retry must not dispatch") }
                )
            }
        }
        compose.onNodeWithText("Off is unsupported by the current model and template.").assertIsDisplayed()
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
        compose.onNodeWithText("Recheck support for Llama").performClick()
        assertEquals(1, checks)
        assertEquals(0, changes)
        compose.onNodeWithText("Switch Llama to Auto").performClick()
        assertEquals(1, changes)
        compose.onNodeWithText("Change settings for Llama").performClick()
        assertEquals(1, settings)
    }

    @Test fun billingRepairTargetsItsNamedProfileWithoutRetryOrContributorRemoval() {
        var billing = 0
        compose.setContent { MaterialTheme { ProfileRecoveryActions(ProfileRecoveryKind.BILLING, "Claude account", null, true, {}, { billing++ }, { error("check") }, { error("auto") }, { error("retry") }) } }
        compose.onNodeWithText("Manage billing for Claude account").performClick()
        assertEquals(1, billing)
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
        compose.onNodeWithText("Check this profile’s API quota and spending limits, or choose another profile. Other replies remain saved.").assertIsDisplayed()
    }

    @Test fun checkingDisablesDuplicateQualificationAndModeChange() {
        compose.setContent { MaterialTheme { ProfileRecoveryActions(ProfileRecoveryKind.REASONING, "Llama", ReasoningRecoveryUiState(checking = true, requested = ReasoningMode.OFF), false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Recheck support for Llama").assertIsNotEnabled()
        compose.onNodeWithText("Switch Llama to Auto").assertIsNotEnabled()
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
    }

    @Test fun profileSettingsNeverChangeExplicitOffDuringAnUnverifiedCheck() {
        var auto = 0
        var recheck = 0
        compose.setContent { MaterialTheme { ReasoningCapabilityContent(ReasoningMode.OFF, ReasoningCapabilityUiState(), { recheck++ }, { auto++ }, true) } }
        assertEquals(0, auto)
        compose.onNodeWithText("Recheck reasoning support").performClick()
        assertEquals(1, recheck)
        assertEquals(0, auto)
        compose.onNodeWithText("Switch to Auto").performClick()
        assertEquals(1, auto)
    }
}
