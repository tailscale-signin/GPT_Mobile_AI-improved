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

    @Test fun unknownPrivateAliasesCannotPassIndependentReviewAndDisableFlagStopsDispatch() {
        val writer = profile("writer", "openai/gpt-4.1")
        val worker = profile("helper", "qwen/qwen3-8b")
        val alias = worker.copy(uid = "reviewer", model = "unresolved-private-alias", compatibleType = ClientType.LLAMA, apiUrl = "https://example.org/private")
        val config = ModelDelegationSettings(enabled = true, targetProfileUid = worker.uid, reviewerProfileUid = alias.uid, reviewMode = DelegationReviewMode.REQUIRED, allowRemoteWorkers = true, localPlatformsOnly = false)
        assertTrue(DelegationAdmissionResolver.resolve(config, listOf(writer, worker, alias), writer).blocked)
        assertFalse(config.copy(orchestrationEnabled = false).normalized().enabled)
        assertFalse(config.copy(orchestrationVersion = 99).normalized().enabled)
        assertFalse(config.copy(reviewMode = DelegationReviewMode.OFF).withDelegationAmount(100).normalized().reviewerEnabled)
    }

    @Test fun previewUsesTheSameReservedReviewerAndWorkerAsAdmission() {
        val writer = profile("writer", "openai/gpt-4.1")
        val reviewer = profile("a-reviewer", "anthropic/claude-sonnet-4.5")
        val worker = profile("z-worker", "qwen/qwen3-8b")
        val config = ModelDelegationSettings(enabled = true, reviewerEnabled = true, reviewerProfileUid = reviewer.uid, allowRemoteWorkers = true, localPlatformsOnly = false)
        val profiles = listOf(writer, reviewer, worker)
        val admission = DelegationAdmissionResolver.resolve(config, profiles, writer)
        assertEquals(worker.uid, admission.worker!!.uid)
        val preview = DelegationPolicyResolver.preview(config, profiles, writer)
        assertTrue(preview.contains("Helper: ${worker.name} · ${worker.model}"))
        assertFalse(preview.contains("Helper: ${reviewer.name} · ${reviewer.model}"))
        assertTrue(preview.contains("Logical research operations:"))
        val denied = DelegationAdmissionResolver.resolve(config.copy(targetProfileUid = worker.uid, allowRemoteWorkers = false, localPlatformsOnly = true), profiles, writer)
        assertTrue(denied.explanation.contains("CLOUD_DENIED"))
    }
}
