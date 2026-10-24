package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationProgressEvidenceTest {
    private val user = MessageV2(id = 10, chatId = 1, content = "Question", platformType = null)
    private val final = AgentRun("final", 1, 10, 11, "a", "test", "model", AgentRunStatus.COMPLETED, ownershipFence = 2, displayRole = ReplyDisplayRole.COMBINED)
    private val answer = MessageV2(id = 11, chatId = 1, content = "The delivered answer", linkedMessageId = 10, platformType = "a", currentRunId = "final", ownershipFence = 2)

    private fun accepted(message: MessageV2) = message.copy(
        timeline = listOf(
            AssistantTimelineItem(
                AssistantTimelineItemType.NOTICE,
                researchDeliveryReceipt = ResearchDeliveryReceipt(ResearchReportValidity.ACCEPTED, true, "packet", researchHash(message.content))
            )
        )
    )

    @Test fun onlyTheCurrentCompletedPrimaryWithVisibleTextCountsAsDelivered() {
        assertTrue(conversationDeliveryEvidence(listOf(final), listOf(user, answer), true).textDelivered)
        for (invalid in listOf(answer.copy(content = ""), answer.copy(content = "Error: Provider unavailable"), answer.copy(currentRunId = "old"), answer.copy(ownershipFence = 1), answer.copy(linkedMessageId = 9))) {
            assertFalse(conversationDeliveryEvidence(listOf(final), listOf(user, invalid), true).textDelivered)
        }
        assertFalse(conversationDeliveryEvidence(listOf(final.copy(status = AgentRunStatus.RUNNING)), listOf(user, answer), true).textDelivered)
    }

    @Test fun oldTurnsAndCombinedContributorsCannotSupplyCompletionEvidence() {
        val newerUser = user.copy(id = 20)
        assertFalse(conversationDeliveryEvidence(listOf(final), listOf(user, answer, newerUser), true).textDelivered)
        val contributor = final.copy(displayRole = ReplyDisplayRole.CONTRIBUTOR)
        assertFalse(conversationDeliveryEvidence(listOf(contributor), listOf(user, accepted(answer)), true).textDelivered)
    }

    @Test fun researchAcceptanceMustMatchTheExactDeliveredPrimaryRevision() {
        val accepted = accepted(answer)
        assertTrue(conversationDeliveryEvidence(listOf(final), listOf(user, accepted), true).reportAccepted)
        assertFalse(conversationDeliveryEvidence(listOf(final), listOf(user, accepted.copy(content = accepted.content + " changed")), true).reportAccepted)
        assertFalse(conversationDeliveryEvidence(listOf(final), listOf(user, answer), true).reportAccepted)
    }

    @Test fun acceptedContributorCannotValidateAnUnacceptedPrimary() {
        val contributor = final.copy(runId = "contributor", assistantMessageId = 12, profileUid = "b", displayRole = ReplyDisplayRole.CONTRIBUTOR)
        val contributedAnswer = accepted(answer.copy(id = 12, platformType = "b", currentRunId = contributor.runId))
        val evidence = conversationDeliveryEvidence(listOf(contributor, final), listOf(user, contributedAnswer, answer), true)
        assertTrue(evidence.textDelivered)
        assertFalse(evidence.reportAccepted)
    }

    @Test fun multiReplyRequiresEveryCurrentReplyToFinishWithContent() {
        val first = final.copy(displayRole = ReplyDisplayRole.MULTI)
        val second = first.copy(runId = "b", assistantMessageId = 12, profileUid = "b")
        val secondAnswer = answer.copy(id = 12, platformType = "b", currentRunId = "b")
        assertTrue(conversationDeliveryEvidence(listOf(first, second), listOf(user, answer, secondAnswer), false).textDelivered)
        assertFalse(conversationDeliveryEvidence(listOf(first, second.copy(status = AgentRunStatus.FAILED)), listOf(user, answer, secondAnswer), false).textDelivered)
        assertFalse(conversationDeliveryEvidence(listOf(first, second), listOf(user, answer, secondAnswer.copy(content = "")), false).textDelivered)
    }

    @Test fun aHistoricalCompletedRunCannotMaskItsRunningReplacement() {
        val old = final.copy(runId = "old", ownershipFence = 1)
        val replacement = final.copy(status = AgentRunStatus.RUNNING)
        assertFalse(conversationDeliveryEvidence(listOf(old, replacement), listOf(user, answer), true).textDelivered)
    }
}
