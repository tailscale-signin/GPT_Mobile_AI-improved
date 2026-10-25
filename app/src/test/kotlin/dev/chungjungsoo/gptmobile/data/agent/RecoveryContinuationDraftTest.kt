package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
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
        val message = MessageV2(id = 2, chatId = 1, content = draft, platformType = "profile", currentRunId = run.runId)
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
