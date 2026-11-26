package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WriterLoadReadinessTest {
    @Test fun streamQuotaPauseSurvivesRestartAndAllowsAChangedCredentialOrAnExplicitRecheck() {
        val directory = Files.createTempDirectory("writer-quota-test").toFile()
        try {
            val profile = PlatformV2(name = "writer", compatibleType = ClientType.OPENAI, token = "credential")
            var now = System.currentTimeMillis()
            val store = KnownWriterFailures(directory) { now }
            val receipt = buildJsonObject {
                put("http_status", 200)
                put("provider_failure_kind", "BUDGET")
                put("provider_budget_kind", "PROVIDER_QUOTA")
            }
            val event = ProviderEvent.Failed("No quota", errorCode = "BILLING_UNAVAILABLE", failureDetails = receipt)
            store.observe(profile, event)
            val restored = KnownWriterFailures(directory) { now }
            val failure = requireNotNull(restored.blocked(profile))
            assertEquals(200, failure.status)
            assertEquals("insufficient_quota", failure.code)
            assertNull(restored.blocked(profile.copy(token = "changed")))
            restored.recheck(profile)
            assertNull(store.blocked(profile))
            store.observe(profile, event.copy(errorCode = "GATEWAY_OUTCOME_UNKNOWN"))
            assertNull(store.blocked(profile))
            store.observe(profile, event)
            now += 299_999L
            assertNotNull(restored.blocked(profile))
            now++
            assertNull(restored.blocked(profile))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun billingDenialSurvivesRestartWithAccountGuidanceAndExplicitRecheck() {
        val directory = Files.createTempDirectory("writer-billing-test").toFile()
        try {
            val profile = PlatformV2(name = "Keyed provider", compatibleType = ClientType.OPENROUTER)
            KnownWriterFailures(directory).observe(profile, "HTTP 402: provider budget restriction")
            val restored = KnownWriterFailures(directory)
            val failure = requireNotNull(restored.blocked(profile))
            org.junit.Assert.assertFalse(failure.retryable)
            org.junit.Assert.assertTrue(failure.blockedMessage().contains("provider allowance"))
            org.junit.Assert.assertFalse(failure.blockedMessage().contains("Check the connection"))
            assertNull(restored.blocked(profile.copy(token = "updated")))
            restored.recheck(profile)
            assertNull(restored.blocked(profile))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun anonymousProviderDenialExpiresAcrossRestartsWithoutChangingKeyedProviderPolicy() {
        val directory = Files.createTempDirectory("free-denial-test").toFile()
        try {
            var now = 100000L
            val profile = PlatformV2(name = "Horde", compatibleType = ClientType.FREE)
            KnownWriterFailures(directory) { now }.observe(profile, "AI Horde: HTTP 403")
            val restored = KnownWriterFailures(directory) { now }
            assertNotNull(restored.blocked(profile))
            now += 59999
            assertNotNull(restored.blocked(profile))
            now++
            assertNull(restored.blocked(profile))
            assertEquals(0, directory.listFiles()!!.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun modelLoadCooldownIsScopedExpiresAndCanBeExplicitlyRechecked() {
        val directory = Files.createTempDirectory("writer-load-test").toFile()
        try {
            var now = 100000L
            val writer = PlatformV2(name = "local server", model = "model-a", compatibleType = ClientType.LLAMA)
            val store = KnownWriterFailures(directory) { now }
            store.observe(writer, "HTTP 500: model failed to load")
            assertEquals("model_load_failed", store.blocked(writer)?.code)
            assertNull(store.blocked(writer.copy(model = "model-b")))
            assertNull(store.blocked(writer.copy(token = "changed")))
            now += 30000
            assertNull(store.blocked(writer))
            store.observe(writer, "HTTP 500: model failed to load")
            store.recheck(writer)
            assertNull(store.blocked(writer))
            store.observe(writer, "HTTP 403: forbidden")
            now += 3600000
            assertNotNull(store.blocked(writer))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun genericServerErrorIsNotMisclassifiedAsModelLoadFailure() {
        val directory = Files.createTempDirectory("writer-transient-test").toFile()
        try {
            val writer = PlatformV2(name = "server", compatibleType = ClientType.LLAMA)
            val store = KnownWriterFailures(directory)
            store.observe(writer, "HTTP 500: transient upstream failure")
            assertNull(store.blocked(writer))
        } finally {
            directory.deleteRecursively()
        }
    }
}
