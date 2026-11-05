package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayOutcomeRecoveryTest {
    private val error = "GATEWAY_OUTCOME_UNKNOWN: connection reset before a job receipt"
    private val failedMessage = MessageV2(
        id = 2,
        chatId = 7,
        content = "Retained partial answer",
        linkedMessageId = 1,
        platformType = "profile",
        currentRunId = "original",
        timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, runError = error, profileFailure = ProfileFailureRecord.classify(error)))
    )
    private val failedRun = AgentRun("original", 7, 1, 2, "profile", "LLAMA", "model", status = AgentRunStatus.FAILED, terminalError = error)

    @Test fun everyRecoveryActionKeepsAnArchivedUnknownOutcomeBlockedBeforeModelOrToolDispatch() = runTest {
        var modelRequests = 0
        var toolDispatches = 0
        val arguments = kotlinx.serialization.json.JsonObject(emptyMap())
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Authorized write", arguments)
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                toolDispatches++
                return AgentToolResult(callId, ToolResultContent.Text("receipt"), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRequests++
                if (exchanges.isEmpty()) emit(ProviderEvent.ToolCall("write", "write", arguments)) else emit(ProviderEvent.TextDelta("Done"))
                emit(ProviderEvent.Completed)
            }
        }
        for (action in TaskRecoveryAction.entries) {
            val descriptor = TaskResumeDescriptor("7:1", 1, "retry", action, allowUndispatchedBudgetRetry = true, allowNewToolBudget = true)
            val replacement = failedMessage.copy(
                currentRunId = "retry",
                content = "",
                timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, recovery = descriptor)),
                revisions = listOf(AssistantRevision(failedMessage.content, createdAt = 1, runId = failedMessage.currentRunId, timeline = failedMessage.timeline))
            )
            val restored = Json.decodeFromString<MessageV2>(Json.encodeToString(MessageV2.serializer(), replacement))
            val failed = runCatching {
                GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(restored), listOf(failedRun))
                AgentRunner().run(provider, listOf(tool)).toList()
            }.exceptionOrNull()
            assertTrue(failed is GatewayOutcomeRecovery.UnreconciledTask)
            assertTrue(failed?.message.orEmpty().startsWith("GATEWAY_OUTCOME_UNKNOWN:"))
        }
        assertEquals(0, modelRequests)
        assertEquals(0, toolDispatches)
    }

    @Test fun savedJobIdentityAloneDoesNotAuthorizeResume() {
        val pending = failedRun.copy(status = AgentRunStatus.INTERRUPTED, gatewayJobId = "job", gatewayBaseUrl = "https://gateway.invalid/v1")
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(failedMessage), listOf(pending)) }.isFailure)
    }

    @Test fun onlyTheMatchingCompletedGatewayReceiptCanReconcileAnArchivedFailure() {
        val reconciled = failedRun.copy(status = AgentRunStatus.COMPLETED, terminalError = null, completedAt = 10, gatewayJobId = "job", gatewayBaseUrl = "https://gateway.invalid/v1")
        val archived = failedMessage.copy(
            currentRunId = "retry",
            timeline = emptyList(),
            revisions = listOf(AssistantRevision(failedMessage.content, createdAt = 1, runId = "original", timeline = failedMessage.timeline))
        )
        for (incomplete in listOf(reconciled.copy(gatewayJobId = null), reconciled.copy(gatewayBaseUrl = null), reconciled.copy(completedAt = null), reconciled.copy(runId = "different"), reconciled.copy(assistantMessageId = 99))) {
            assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(archived), listOf(incomplete)) }.isFailure)
        }
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(archived), listOf(reconciled)) }.isSuccess)
    }

    @Test fun durableRunFailureBlocksEvenWhenTheMessageWindowOmitsItsTimeline() {
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, emptyList(), listOf(failedRun)) }.isFailure)
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(failedMessage), emptyList()) }.isFailure)
    }

    @Test fun hostVerifiedIsolatedSavedGenerationClearsOnlyItsGatewayTransportFence() {
        val recovered = failedRun.copy(
            status = AgentRunStatus.EVIDENCE_MISSING,
            terminalError = GatewayOutcomeRecovery.ISOLATED_RESULT_PREFIX + " Server tools were forbidden; task verification remains pending.",
            completedAt = 10,
            gatewayJobId = "job",
            gatewayBaseUrl = "https://gateway.invalid/v1"
        )
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(failedMessage), listOf(recovered)) }.isSuccess)
        for (unproven in listOf(
            recovered.copy(terminalError = "GATEWAY_RECOVERED_RESULT_UNVERIFIED"),
            recovered.copy(gatewayJobId = null),
            recovered.copy(completedAt = null),
            recovered.copy(assistantMessageId = 9),
            recovered.copy(status = AgentRunStatus.PARTIAL)
        )) {
            assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(failedMessage), listOf(unproven)) }.isFailure)
        }
        assertEquals(AgentRunStatus.EVIDENCE_MISSING, recovered.status)
    }

    @Test fun controlsRetainOriginalTaskIdentityWithoutBlockingUnrelatedTasksOrChats() {
        val resumed = failedMessage.copy(linkedMessageId = 3, timeline = failedMessage.timeline + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, recovery = TaskResumeDescriptor("7:1", 1, "original", TaskRecoveryAction.CONTINUE_RUN)))
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(resumed), emptyList()) }.isFailure)
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 9, listOf(failedMessage), listOf(failedRun)) }.isSuccess)
        assertTrue(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(8, 1, listOf(failedMessage), listOf(failedRun)) }.isSuccess)
    }

    @Test fun quotedModelTextDoesNotBecomeAStoredGatewayFailure() {
        val explanation = failedMessage.copy(content = "The diagnostic code is GATEWAY_OUTCOME_UNKNOWN.", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = error, modelAuthored = true)))
        assertFalse(runCatching { GatewayOutcomeRecovery.requireTaskReconciled(7, 1, listOf(explanation), emptyList()) }.isFailure)
    }
}
