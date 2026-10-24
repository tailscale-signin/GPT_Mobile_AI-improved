package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.AcceptedResearchTask
import dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState
import dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord
import dev.chungjungsoo.gptmobile.data.research.ResearchOperationalReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchInterruptedReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptedCardBlocksDirectRetryAndOffersReviewWithoutDispatch() {
        var retries = 0
        var reviews = 0
        compose.setContent {
            MaterialTheme { ResearchAttemptProgressCard(interrupted(), {}, { retries++ }, { reviews++ }) }
        }
        compose.onNodeWithText("Retry research").assertIsNotEnabled().performClick()
        assertEquals(0, retries)
        compose.onNodeWithText("Review interrupted requests").assertIsDisplayed().performClick()
        assertEquals(1, reviews)
        assertEquals(0, retries)
    }

    @Test fun reviewedAuthorizationRequiresAcknowledgementAndSubmitsSameActionOnlyOnce() {
        val review = ResearchInterruptedReview.capture(interrupted(dispatchedRuns = 8), "review-action", mapOf("run-a" to run()), mapOf("a" to "Alpha"), listOf(reply()))
        val actions = mutableListOf<String>()
        compose.setContent { MaterialTheme { ResearchInterruptedRequestsDialog(review, {}, { actions.add(it) }) } }
        compose.onNodeWithText("Run: run-a").assertExists()
        compose.onNodeWithText("Local outcome: INTERRUPTED").assertExists()
        compose.onAllNodesWithText("Remote acceptance: UNKNOWN").assertCountEquals(8)
        compose.onNodeWithText("3 readable sources retained. Saved replies: 1.").assertExists()
        compose.onNodeWithText("Retained delivery receipts: EVIDENCE_ONLY").assertExists()
        compose.onNodeWithText("Start new attempt").assertIsNotEnabled().performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithContentDescription("Acknowledge unknown remote outcomes and authorize a new attempt")
            .assertIsNotDisplayed().performScrollTo().assertIsDisplayed().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Start new attempt").assertIsEnabled().performClick()
        compose.onNodeWithText("Start new attempt").assertIsNotEnabled().performClick()
        assertEquals(listOf("review-action"), actions)
        assertEquals(ResearchAttemptState.INTERRUPTED, review.execution.attempt.state)
        assertTrue(review.requests.all { it.remoteAcceptance == "UNKNOWN" })
    }

    @Test fun reviewScopesReceiptsToCurrentAttemptAndNeverConvertsLocalSuccessToRemoteAcceptance() {
        val execution = interrupted().let { record ->
            record.copy(receipts = record.receipts + ResearchOperationalReceipt(99, "old-attempt", -1, ResearchAttemptState.COMPLETED, runId = "run-a", profileUid = "b", dispatched = true, time = 1))
        }
        val review = ResearchInterruptedReview.capture(execution, "action", mapOf("run-a" to run().copy(status = "COMPLETED", gatewayJobId = "saved-job")), mapOf("a" to "Alpha", "b" to "Beta"), listOf(reply().copy(content = "Changed after validation")))
        assertEquals(setOf("run-a"), review.requests.map { it.runId }.toSet())
        assertEquals(listOf("Alpha"), review.requests.single().profiles)
        assertEquals("COMPLETED", review.requests.single().localOutcome)
        assertEquals("UNKNOWN", review.requests.single().remoteAcceptance)
        assertEquals("saved-job", review.requests.single().gatewayJobId)
        assertTrue(review.retainedDeliveryStatuses.isEmpty())
    }

    private fun interrupted(dispatchedRuns: Int = 1): ResearchExecutionRecord {
        val task = AcceptedResearchTask(
            taskId = researchHash("task:root"), rootWorkId = "root", chatId = 7, originatingUserMessageId = 1,
            taskText = "Review the available evidence", intent = "REQUIRED", participantUids = listOf("a", "b"), primaryUid = "a",
            settingsRevision = "policy", sourceAuthorityRevision = "scope", acceptedAt = 10, targetSources = 5
        )
        val accepted = ResearchExecutionRecord.accept(task)
        val dispatched = (1..dispatchedRuns).fold(accepted) { record, number ->
            record.transition(accepted.attempt.attemptId, 0, ResearchAttemptState.ANALYZING, 20L + number, runId = if (number == 1) "run-a" else "run-$number", profileUid = "a", dispatched = true, checkpointId = "checkpoint", evidenceRevision = "evidence-v1", readableSources = 3)
        }
        return dispatched.transition(accepted.attempt.attemptId, 0, ResearchAttemptState.INTERRUPTED, 30)
    }

    private fun run() = AgentRun("run-a", 7, 1, 2, "a", "CUSTOM", "model", status = "INTERRUPTED")

    private fun reply(): MessageV2 {
        val text = "Saved evidence remains readable."
        val receipt = ResearchDeliveryReceipt(ResearchReportValidity.EVIDENCE_ONLY, true, "packet", researchHash(text))
        return MessageV2(id = 2, chatId = 7, platformType = "a", linkedMessageId = 1, content = text, timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, researchDeliveryReceipt = receipt)))
    }
}
