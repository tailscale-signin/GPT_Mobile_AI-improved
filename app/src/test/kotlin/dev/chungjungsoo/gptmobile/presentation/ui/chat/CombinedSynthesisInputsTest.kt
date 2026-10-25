package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisInputsTest {
    private fun message(uid: String, content: String) = MessageV2(content = content, platformType = uid, currentRunId = uid)
    private fun run(id: String, status: String = AgentRunStatus.COMPLETED) = AgentRun(id, 1, 1, 2, id, "test", "test", status = status)
    private fun inputs(messages: List<MessageV2>, runs: Map<String, AgentRun> = messages.mapNotNull { it.currentRunId }.associateWith { run(it) }) =
        combinedSynthesisInputs(messages, runs, emptyMap(), emptyMap())

    @Test fun helperContributionsUseLatestResponsesEvenWhenViewingAnOldRevision() {
        val lead = message("lead", "Primary finding.")
        val helper = message("helper", "Additional finding.").copy(revisions = listOf(AssistantRevision("Old finding.", createdAt = 1)), activeRevisionIndex = 0)
        val sources = requireNotNull(inputs(listOf(lead, helper)))
        assertEquals(listOf("Primary finding.", "Additional finding."), sources.map { it.content })
        val merged = mergeCombinedResponses(sources)
        assertTrue(merged.contains("Primary finding."))
        assertTrue(merged.contains("Additional finding."))
    }

    @Test fun synthesisWaitsForEveryActiveProfileAndRunRecord() {
        val messages = listOf(message("lead", "Primary"), message("helper", "Partial"))
        assertNull(inputs(messages, mapOf("lead" to run("lead"), "helper" to run("helper", AgentRunStatus.RUNNING))))
        assertNull(inputs(messages, mapOf("lead" to run("lead"))))
    }

    @Test fun quotaWaitCannotHideUsableRepliesFromTheExplicitContinueAction() {
        val replies = listOf(message("lead", "Portugal established its independent kingdom in the twelfth century."), message("limited", ""), message("queued", ""))
        val runs = mapOf("lead" to run("lead"), "limited" to run("limited", AgentRunStatus.WAITING_PROVIDER_LIMIT), "queued" to run("queued", AgentRunStatus.WAITING_REMOTE_JOB))
        // Normal synthesis still needs the durable per-turn choice before excluding a profile.
        assertNull(inputs(replies, runs))
        val available = availableCombinedResponses(replies, runs)
        assertEquals(listOf("lead"), available.map { it.platformType })
        val sources = requireNotNull(inputs(available, runs))
        assertEquals(replies.first().content, sources.single().content)
        assertNull(inputs(availableCombinedResponses(replies.drop(1), runs), runs))
        assertNull(inputs(availableCombinedResponses(replies, runs + ("lead" to run("lead", AgentRunStatus.RUNNING))), runs + ("lead" to run("lead", AgentRunStatus.RUNNING))))
    }

    @Test fun transportCompleteShortOverviewIsRetainedAsPartialForAnExplicitLongRequest() {
        val reply = message("lead", "Portugal established an independent kingdom in the twelfth century. Maritime expansion later connected its ports with Africa, Asia and the Americas.")
        val sources = requireNotNull(combinedSynthesisInputs(listOf(reply), mapOf("lead" to run("lead")), emptyMap(), emptyMap(), "The history of Portugal in 5000 words"))
        assertEquals(reply.content, sources.single().content)
        assertEquals(ContributionDisposition.USABLE_PARTIAL, sources.single().disposition)
        assertEquals("PARTIAL", sources.single().contentCompleteness)
        assertEquals(AgentRunStatus.COMPLETED, sources.single().completionStatus)
        val assessment = CombinedContributionUsability.assess(AgentRunStatus.COMPLETED, reply.content, request = "The history of Portugal in at most 5000 words", finishReason = "stop")
        assertFalse(assessment.usablePartial)
    }

    @Test fun completedSynthesisDoesNotRepeatWhenAHelperChanges() {
        val sources = listOf(CombinedModelResponse("lead", "AI", content = "Primary"), CombinedModelResponse("helper", "AI", content = "Old helper"))
        val lead = message("lead", "Combined").copy(currentRunId = "combined-synthesis:1", combinedSources = sources)
        assertNull(inputs(listOf(lead, message("helper", "Old helper"))))
        assertNull(inputs(listOf(lead, message("helper", "New helper"))))
    }

    @Test fun failedOrCanceledSynthesisNeverAutomaticallyLoopsOnUnchangedSources() {
        val sources = listOf(CombinedModelResponse("lead", "AI", content = "Primary"), CombinedModelResponse("helper", "AI", content = "Helper"))
        val lead = message("lead", "").copy(currentRunId = "combined-synthesis:1", combinedSources = sources)
        listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED).forEach { status ->
            val runs = mapOf("combined-synthesis:1" to run("combined-synthesis:1", status), "helper" to run("helper"))
            assertNull(inputs(listOf(lead, message("helper", "Helper")), runs))
            assertNull(inputs(listOf(lead, message("helper", "Changed helper")), runs))
        }
    }

    @Test fun rawToolMarkupAndTerminalProviderErrorsAreNotAdmitted() {
        assertFalse(CombinedContributionQuality.assess(AgentRunStatus.COMPLETED, "<tool_call>{\"name\":\"write\"}</tool_call>").admitted)
        assertFalse(CombinedContributionQuality.assess(AgentRunStatus.FAILED, "A partial sentence from a failed request").admitted)
        assertTrue(CombinedContributionQuality.assess(AgentRunStatus.COMPLETED, "A concise but usable answer.").admitted)
    }

    @Test fun acceptedResearchReceiptRequiresExactLatestContributorBytes() {
        val report = "The captured study supports this limited finding."
        val receipt = receipt(report)
        val accepted = message("lead", report).copy(timeline = listOf(receipt))
        assertEquals(ContributionDisposition.ACCEPTED_REPORT, requireNotNull(inputs(listOf(accepted))).single().disposition)
        val appended = accepted.copy(content = "$report\nA later unchecked claim was appended.")
        assertEquals(ContributionDisposition.USABLE_PARTIAL, requireNotNull(inputs(listOf(appended))).single().disposition)
        assertFalse(receipt.researchDeliveryReceipt!!.matchesRevision("$report "))
        assertNull(requireNotNull(receipt.researchDeliveryReceipt).displayValidityFor(null))
    }

    @Test fun staleKnownResearchReceiptDoesNotInheritCompletedStatusOrEarlierAcceptance() {
        val report = "The captured study supports this limited finding."
        val latest = message("lead", "$report\nChanged interpretation.").copy(
            timeline = listOf(receipt(report, ResearchReportValidity.EVIDENCE_ONLY)),
            revisions = listOf(AssistantRevision(report, createdAt = 1, runId = "earlier", timeline = listOf(receipt(report)))),
            activeRevisionIndex = 0
        )
        assertEquals(ContributionDisposition.USABLE_PARTIAL, requireNotNull(inputs(listOf(latest))).single().disposition)
    }

    @Test fun preservedContributorUsesItsOwnReceiptAndRawRevisionInsteadOfTheEditorReceipt() {
        val report = "The contributor found a limited supported result."
        val source = CombinedModelResponse("lead", "Lead", content = report, attemptId = "lead", rawContentDigest = researchHash(report), disposition = ContributionDisposition.ACCEPTED_REPORT)
        val editor = message("lead", "The editor report needs correction.").copy(
            currentRunId = "combined-synthesis:1",
            combinedSources = listOf(source),
            timeline = listOf(receipt("The editor report needs correction.", ResearchReportValidity.REJECTED)),
            revisions = listOf(AssistantRevision(report, createdAt = 1, runId = "lead", timeline = listOf(receipt(report))))
        )
        assertEquals(ContributionDisposition.ACCEPTED_REPORT, combinedContributionDisposition(editor, source, AgentRunStatus.COMPLETED))
        val staleOriginal = editor.copy(revisions = listOf(editor.revisions.single().copy(content = "$report\nUnchecked addition.")))
        assertEquals(ContributionDisposition.USABLE_PARTIAL, combinedContributionDisposition(staleOriginal, source, AgentRunStatus.COMPLETED))
    }

    private fun receipt(text: String, validity: ResearchReportValidity = ResearchReportValidity.ACCEPTED) = AssistantTimelineItem(
        AssistantTimelineItemType.NOTICE,
        researchDeliveryReceipt = ResearchDeliveryReceipt(validity, true, "packet", researchHash(text))
    )
}
