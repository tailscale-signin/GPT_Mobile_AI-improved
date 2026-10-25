package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryContinuationDraftTest {
    private val descriptor = TaskResumeDescriptor("1:1", 1, "resume", TaskRecoveryAction.CONTINUE_RUN)

    private fun recovery(draft: String, status: String = "INTERRUPTED", authorized: Boolean = true): ResponseRecoveryContext {
        val run = AgentRun("prior", 1, 1, 2, "profile", "FREE", "model", status = status)
        val message = MessageV2(id = 2, chatId = 1, content = "", platformType = "profile", currentRunId = descriptor.generationId, revisions = listOf(AssistantRevision(draft, createdAt = 1, runId = run.runId)))
        return ResponseRecoveryContext(listOf(run), listOf(message), emptyList(), authorization = { authorized })
    }

    @Test fun aTypedQuotaResumeRequiresTheExactAuthorizedPrefixIncludingWhitespace() = runTest {
        val draft = "  First retained paragraph.\n\nSecond retained paragraph.\n"
        val typed = descriptor.copy(continuationPrefixDigest = researchHash(draft))
        assertEquals(draft, recoveryContinuationDraft(recovery(draft), typed, "Original request"))
        assertTrue(runCatching { recoveryContinuationDraft(recovery(draft.trim()), typed, "Original request") }.exceptionOrNull()?.message.orEmpty().startsWith("PROVIDER_WAIT_PREFIX_CHANGED"))
        assertTrue(runCatching { recoveryContinuationDraft(null, typed, "Original request") }.exceptionOrNull()?.message.orEmpty().startsWith("PROVIDER_WAIT_PREFIX_CHANGED"))
        assertTrue(runCatching { recoveryContinuationDraft(recovery(draft, authorized = false), typed, "Original request") }.exceptionOrNull() is SavedEvidenceAccessDenied)
    }

    @Test fun manualRetryExtendsUnfinishedOrShortAnswersButNotAnAdequateCompletedAnswer() = runTest {
        val draft = "A retained answer."
        assertEquals(draft, recoveryContinuationDraft(recovery(draft), descriptor, "Answer briefly"))
        assertEquals(draft, recoveryContinuationDraft(recovery(draft, "COMPLETED"), descriptor, "Write 1000 words"))
        assertEquals("", recoveryContinuationDraft(recovery(draft, "COMPLETED"), descriptor, "Answer briefly"))
        assertEquals("", recoveryContinuationDraft(recovery(draft), null, "Answer briefly"))
    }

    @Test fun absentContinuationIdentityDoesNotChangeLegacySerializedDescriptors() {
        val codec = Json { encodeDefaults = true }
        assertFalse(codec.encodeToString(descriptor).contains("continuationPrefixDigest"))
        val typed = descriptor.copy(continuationPrefixDigest = "a".repeat(64))
        assertEquals(typed, codec.decodeFromString<TaskResumeDescriptor>(codec.encodeToString(typed)))
    }

    @Test fun anEmptySeparateContinueSlotDoesNotRestoreOrCreditAnEarlierSlotsPrefixTwice() = runTest {
        val originalText = "original ".repeat(1200)
        val original = AgentRun("original", 1, 1, 2, "profile", "FREE", "model", status = "COMPLETED")
        val empty = original.copy(runId = "empty-continue", userMessageId = 3, assistantMessageId = 4, status = "FAILED")
        val earlier = MessageV2(id = 2, chatId = 1, linkedMessageId = 1, content = originalText, platformType = "profile", currentRunId = original.runId)
        val current = MessageV2(id = 4, chatId = 1, linkedMessageId = 3, content = "", platformType = "profile", currentRunId = descriptor.generationId, revisions = listOf(AssistantRevision("", createdAt = 2, runId = empty.runId)))
        val recovered = ResponseRecoveryContext(listOf(original, empty), listOf(earlier, current), emptyList())

        assertTrue(recovered.content.contains(originalText.trim()))
        assertEquals(1200, previousRecoveryAnswerWords(listOf(listOf(earlier), listOf(current)), "profile", 1, descriptor.generationId))
        assertEquals("", recoveryContinuationDraft(recovered, descriptor, "Write 5000 words"))
        assertTrue(runCatching { recoveryContinuationDraft(recovered, descriptor.copy(continuationPrefixDigest = researchHash(originalText)), "Write 5000 words") }.isFailure)
    }

    @Test fun aSameSlotOlderPrefixSurvivesAnEmptyAttemptAndCannotBeReplacedByAnotherSlot() = runTest {
        val retained = "  Retained answer from this slot.\n"
        val prior = AgentRun("prior", 1, 3, 4, "profile", "FREE", "model", status = "INTERRUPTED")
        val empty = prior.copy(runId = "empty", status = "FAILED")
        val other = prior.copy(runId = "other", assistantMessageId = 6)
        val slot = MessageV2(id = 4, chatId = 1, linkedMessageId = 3, content = "", platformType = "profile", currentRunId = descriptor.generationId, revisions = listOf(AssistantRevision("", createdAt = 3, runId = empty.runId), AssistantRevision(retained, createdAt = 2, runId = prior.runId)))
        val otherSlot = MessageV2(id = 6, chatId = 1, linkedMessageId = 5, content = "Newer answer in a different slot.", platformType = "profile", currentRunId = other.runId)
        val recovered = ResponseRecoveryContext(listOf(prior, empty, other), listOf(slot, otherSlot), emptyList())
        assertEquals(retained, recoveryContinuationDraft(recovered, descriptor, "Write 5000 words"))
        assertEquals(retained, recoveryContinuationDraft(recovered, descriptor.copy(continuationPrefixDigest = researchHash(retained)), "Write 5000 words"))
    }

    @Test fun resumingASeparateContinueSlotKeepsCreditForEarlierAnswersWithoutDoubleCountingItsPrefix() {
        val original = MessageV2(id = 2, chatId = 1, linkedMessageId = 1, content = "original ".repeat(1200), platformType = "profile", currentRunId = "original")
        val continued = MessageV2(id = 4, chatId = 1, linkedMessageId = 3, content = "continued ".repeat(2000), platformType = "profile", currentRunId = "resume")
        val otherProfile = original.copy(id = 5, platformType = "other")
        val olderTask = original.copy(id = 6, linkedMessageId = 0)
        val answers = listOf(listOf(original, otherProfile, olderTask), listOf(continued), listOf(original))
        assertEquals(1200, previousRecoveryAnswerWords(answers, "profile", 1, "resume"))
        assertEquals(0, previousRecoveryAnswerWords(listOf(listOf(original)), "profile", 1, "original"))
        assertEquals(3200, previousRecoveryAnswerWords(answers, "profile", 1, "new-empty-slot"))
    }
}
