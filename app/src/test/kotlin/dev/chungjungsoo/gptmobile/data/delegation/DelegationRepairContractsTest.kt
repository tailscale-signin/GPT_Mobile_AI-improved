package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationRepairContractsTest {
    private fun profile(id: String, model: String) = PlatformV2(uid = id, name = id, compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1", model = model, enabled = true)

    @Test fun unknownForbiddenDoesNotBecomeBadKeyOrBudget() {
        assertEquals(ProviderFailureKind.FORBIDDEN_UNKNOWN, ProviderFailure.parse(403, "private raw response").kind)
        assertEquals(ProviderFailureKind.BUDGET, ProviderFailure.parse(403, """{"error":{"code":"budget_exceeded"}}""").kind)
        assertEquals(ProviderFailureKind.AUTHENTICATION, ProviderFailure.parse(401).kind)
        assertFalse(ProviderFailure.parse(403).retryable)
        assertTrue(ProviderFailure.parse(429, retryAfterMillis = 10000).retryable)
    }

    @Test fun knownWriterDenialSurvivesRestartAndExplicitRecheckClearsIt() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val writer = profile("writer", "openai/gpt-5.6").copy(token = "synthetic-token")
            KnownWriterFailures(directory).observe(writer, "HTTP 403: forbidden")
            val restored = KnownWriterFailures(directory)
            assertEquals(ProviderFailureKind.FORBIDDEN_UNKNOWN, restored.blocked(writer)!!.kind)
            assertFalse(directory.listFiles()!!.any { it.readText().contains("synthetic-token") })
            assertNull(restored.blocked(writer.copy(model = "openai/gpt-4.1")))
            restored.recheck(writer)
            assertNull(restored.blocked(writer))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun requiredReviewerBlocksBeforeResearchAndBestEffortExplainsLimitation() {
        val writer = profile("writer", "openai/gpt-5.6")
        val worker = profile("worker", "anthropic/claude-sonnet-4.5")
        val config = ModelDelegationSettings(enabled = true, targetProfileUid = worker.uid, reviewerEnabled = true, reviewerProfileUid = writer.uid, allowRemoteWorkers = true, localPlatformsOnly = false, reviewMode = DelegationReviewMode.REQUIRED)
        assertTrue(DelegationAdmissionResolver.resolve(config, listOf(writer, worker), writer).blocked)
        val best = DelegationAdmissionResolver.resolve(config.copy(reviewMode = DelegationReviewMode.BEST_EFFORT), listOf(writer, worker), writer)
        assertFalse(best.blocked)
        assertTrue(best.warning!!.contains("unavailable"))
    }

    @Test fun cancellationCauseIsFirstWriterWinsAndUnknownIsNotUserStop() {
        val context = DelegationCancellationContext("chat:1", "generation:2")
        assertNull(context.record)
        context.mark(CancellationOrigin.PERMISSION_REVOKED)
        context.mark(CancellationOrigin.USER_WHOLE_RUN)
        assertEquals(CancellationOrigin.PERMISSION_REVOKED, context.record!!.origin)
        assertEquals("chat:1", context.record!!.rootWorkId)
    }
}
