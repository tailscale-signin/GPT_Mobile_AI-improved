package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.agent.COMBINED_RECOVERY_MARKER
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class CombinedCleanPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun answerOnlyProjectionKeepsOrdinarySharedTextAndOmitsAllProcessSemantics() {
        val answer = "The shared finding remains readable."
        compose.setContent {
            MaterialTheme {
                AssistantChronologicalContent(
                    timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Shared diagnostic trace"), AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Hidden reasoning"), AssistantTimelineItem(AssistantTimelineItemType.TEXT, answer)),
                    toolEvents = emptyList(), fallbackText = answer, fallbackThoughts = "Hidden reasoning", contentIdentity = "combined", isLoading = false,
                    debugMode = true, showExecutionTrace = false, showReasoning = true, expanded = true, onExpandedChange = {}
                )
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(answer).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(answer).assertIsDisplayed()
        compose.onNodeWithText("Shared diagnostic trace").assertDoesNotExist()
        compose.onNodeWithText("Hidden reasoning").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").assertDoesNotExist()
        compose.onNodeWithContentDescription("Collapse activity").assertDoesNotExist()
    }

    @Test fun groupedFailuresExposeConciseNamedActionsAndOneContinue() {
        val failures = listOf(
            CombinedProfileFailure(1, "alpha", "Alpha", "secret-model-id", "a", "HTTP 502 full provider body", "stack trace", true),
            CombinedProfileFailure(1, "beta", "Beta", "another-model-id", "b", "unsupported", "technical configuration", false)
        )
        var retry: String? = null
        var remove: String? = null
        var excluded: Pair<Int, String>? = null
        var continued = 0
        compose.setContent {
            MaterialTheme { CombinedFailuresDialog(failures, ProfileColorRegistry.ensure(emptyMap(), listOf("alpha", "beta")), { _, uid -> retry = uid }, { remove = it }, { continued++ }, {}, true, { turn, uid -> excluded = turn to uid }) }
        }
        compose.onNodeWithText("2 profiles need attention").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry Alpha").performClick()
        assertEquals("alpha", retry)
        compose.onNodeWithText("Exclude Alpha from this turn").performClick()
        assertEquals(1 to "alpha", excluded)
        assertEquals(null, remove)
        compose.onNodeWithContentDescription("Retry Beta").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove Beta from this chat").performClick()
        assertEquals("beta", remove)
        compose.onNodeWithText("secret-model-id").assertDoesNotExist()
        compose.onNodeWithText("stack trace").assertDoesNotExist()
        compose.onNodeWithText("Review later").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
    }

    @Test fun allWaitingAlertsCanCloseWithoutCombiningOrExcludingProfiles() {
        val failure = CombinedProfileFailure(1, "alpha", "Alpha", "model", "waiting-attempt", "RATE_LIMIT", "Provider reset pending", true, retryAfterMillis = Long.MAX_VALUE, category = "RATE_LIMIT")
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                CombinedFailuresDialog(
                    failures = listOf(failure),
                    identities = emptyMap(),
                    onRetry = { _, _ -> error("A pending quota cannot retry yet") },
                    onRemove = { error("Dismissal must retain the profile") },
                    onContinue = { error("No reply is available to combine") },
                    onDismiss = { dismissed++ },
                    canContinue = false,
                    onExclude = { _, _ -> error("Dismissal must leave turn membership unchanged") }
                )
            }
        }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Keep waiting").performClick()
        assertEquals(1, dismissed)
        compose.onNodeWithContentDescription("Close profile alerts").performClick()
        assertEquals(2, dismissed)
    }

    @Test fun normalCopyAndExportsNeverExposeDiagnosticRecoveryAppendices() {
        val answer = "## Findings\nThe shared finding remains readable.\n\n$COMBINED_RECOVERY_MARKER\nHidden record"
        val response = MessageV2(content = answer, platformType = "writer", currentRunId = "combined-synthesis:one")
        val visible = assistantExportText(response, ChatExportFormat.MARKDOWN, debugMode = true)
        // The primary projection is independent of the app-wide Debug Mode switch.
        assertEquals(assistantExportText(response, ChatExportFormat.MARKDOWN, debugMode = false), visible)
        assertFalse(visible.contains("Hidden record"))
        assertEquals("## Findings\nThe shared finding remains readable.", visible)
    }

    @Test fun missingSavedPolicyOffersSavedDataRecoveryWithoutRetryOrProfileRemoval() {
        val prior = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord("UPSTREAM_UNAVAILABLE", retryAllowed = true)
        val response = MessageV2(id = 3, chatId = 1, platformType = "alpha", linkedMessageId = 2, content = "", currentRunId = "failed", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = prior)))
        val run = dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("failed", 1, 2, 3, "alpha", "CUSTOM", "model", "FAILED", terminalError = "POLICY_SNAPSHOT_UNRESOLVED_IMPORT: missing saved data")
        val failure = combinedProfileFailures(2, "Explain the result", listOf(response), mapOf(run.runId to run), mapOf("alpha" to "Alpha"), emptyMap()).single()
        assertEquals("SAVED_POLICY_UNAVAILABLE", failure.category)
        assertEquals(true, failure.applicationFailure)
        assertFalse(failure.retryAllowed)
        compose.setContent {
            MaterialTheme { CombinedFailuresDialog(listOf(failure), emptyMap(), { _, _ -> error("Retry must remain disabled") }, { error("An app data failure cannot remove a profile") }, {}, {}, false) }
        }
        compose.onNodeWithText("Saved request unavailable").assertIsDisplayed()
        compose.onNodeWithText("Restore the complete backup or start a new request. Other replies are saved.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry Alpha").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove Alpha from this chat").assertIsNotEnabled()
    }
}
