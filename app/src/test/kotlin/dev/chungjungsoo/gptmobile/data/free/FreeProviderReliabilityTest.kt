package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRateLimitException
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import dev.chungjungsoo.gptmobile.data.network.gradioErrorDetails
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProviderReliabilityTest {
    @Test fun legacyAndRefreshedCatalogsKeepSeparateContextAndOutputLimits() {
        val legacy = Json.decodeFromString<FreeModelOption>("""{"id":"gpt-oss-20b"}""")
        val known = FreeModelCatalog.defaults(FreeAiProvider.BRIDGENODE).first()
        assertEquals(8000, FreeCatalogState.mergeLimits(legacy, known).contextTokens)
        val decoded = FreeCatalogState.decodeModels(FreeAiProvider.BRIDGENODE, Json.parseToJsonElement("""{"data":[{"id":"gpt-oss-20b","isFree":true,"context_window":8000,"max_output_tokens":4096}]}""")).single()
        assertEquals(8000, decoded.contextTokens)
        assertEquals(4096, decoded.outputTokens)
        assertEquals(decoded, Json.decodeFromString<FreeModelOption>(Json.encodeToString(decoded)))
        assertEquals(decoded, FreeCatalogState.mergeLimits(legacy, decoded))
    }

    @Test fun providerCooldownSurvivesRestartAndExpiresWithoutPromisingAvailability() {
        var now = 1_000_000L
        val file = Files.createTempDirectory("free-cooldown").resolve("state.json").toFile()
        try {
            val first = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            first.defer(FreeAiProvider.LLM7, "33720")
            val deadline = first.cooldownUntilMillis(FreeAiProvider.LLM7)
            val restarted = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            assertEquals(deadline, restarted.cooldownUntilMillis(FreeAiProvider.LLM7))
            assertThrows(FreeAiRateLimitException::class.java) { restarted.reserve(FreeAiProvider.LLM7) }
            assertNull(restarted.cooldownUntilMillis(FreeAiProvider.KILO))
            now = deadline!!
            assertNull(restarted.cooldownUntilMillis(FreeAiProvider.LLM7))
            restarted.reserve(FreeAiProvider.LLM7)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun respondingFailuresAreReachableAndRepeatedDenialsPauseOnlyTheirModel() {
        val provider = FreeAiProvider.OPENCODE
        FreeCatalogState.recordResponse(provider, "model-a")
        try {
            FreeCatalogState.recordHttpFailure(provider, 400, "Invalid context")
            assertEquals(true, FreeCatalogState.status.value[provider]?.reachable)
            assertEquals(FreeGenerationReadiness.INVALID_REQUEST, FreeCatalogState.status.value[provider]?.readiness)
            FreeCatalogState.recordHttpFailure(provider, 429, "Quota", 9999)
            assertEquals(true, FreeCatalogState.status.value[provider]?.reachable)
            assertEquals(FreeGenerationReadiness.RATE_LIMITED, FreeCatalogState.status.value[provider]?.readiness)
            repeat(2) { FreeCatalogState.recordHttpFailure(provider, 403, "Denied", model = "model-a") }
            assertThrows(IllegalStateException::class.java) { FreeCatalogState.requireGenerationReady(provider, "model-a") }
            assertThrows(IllegalStateException::class.java) { FreeCatalogState.requireGenerationReady(provider, "model-a") }
            FreeCatalogState.requireGenerationReady(provider, "model-b")
            FreeCatalogState.recordFailure(provider, "DNS", FreeGenerationReadiness.UNREACHABLE, reachable = false)
            assertEquals(false, FreeCatalogState.status.value[provider]?.reachable)
        } finally {
            FreeCatalogState.recordResponse(provider, null)
        }
    }

    @Test fun hordeCodesAndGradioDetailsArePreservedWithoutCredentials() {
        assertEquals("kudosupfront", ProviderFailure.parse(403, """{"rc":"KudosUpfront"}""").code)
        assertTrue(ProviderFailure.parse(403, """{"rc":"KudosUpfront"}""").message().contains("anonymous allowance"))
        assertEquals("sharedkeyempty", ProviderFailure.parse(403, """{"rc":"SharedKeyEmpty"}""").code)
        assertEquals("anonforbidden", ProviderFailure.parse(403, """{"rc":"AnonForbidden"}""").code)
        assertTrue(gradioErrorDetails("null").contains("No more specific reason"))
        assertTrue(gradioErrorDetails("""{"error":{"code":"QuotaExceeded","message":"Downstream quota reached"}}""").contains("Downstream quota reached"))
        assertFalse(gradioErrorDetails(""""Authorization: Bearer secret-value"""").contains("secret-value"))
    }

    @Test fun boundedFailuresAreExportableWithLoggingOffButTemporaryChatsAreExcluded() {
        val file = Files.createTempDirectory("provider-failures").resolve("failures.json").toFile()
        FreeProviderDiagnostics.initialize(file)
        FreeProviderDiagnostics.clear()
        val config = ProviderRequestConfig(FreeAiProvider.OPENCODE.apiUrl, "credential-value", freeProvider = FreeAiProvider.OPENCODE, queueScope = "42:turn", queuePersistent = true, freeDiagnostics = FreeRequestDiagnostics())
        try {
            repeat(70) {
                FreeProviderDiagnostics.record(config, "glm-5.3-flash-free", "Access denied", 403, """{"error":{"code":"route_forbidden","message":"credential-value denied"}}""", { key ->
                    if (key == "X-Request-ID") {
                        "trace-123"
                    } else if (key == "Content-Type") {
                        "application/json"
                    } else {
                        null
                    }
                })
            }
            val records = FreeProviderDiagnostics.snapshot(42)
            assertEquals(64, records.size)
            FreeProviderDiagnostics.initialize(file)
            assertEquals(records, FreeProviderDiagnostics.snapshot(42))
            assertEquals("trace-123", records.last().requestId)
            assertEquals("route_forbidden", records.last().code)
            assertFalse(file.readText().contains("credential-value"))
            FreeProviderDiagnostics.record(config.copy(queuePersistent = false, queueScope = "43:private"), "model", "private failure")
            assertTrue(FreeProviderDiagnostics.snapshot(43).isEmpty())
            FreeProviderDiagnostics.forgetChat(42)
            assertTrue(FreeProviderDiagnostics.snapshot().isEmpty())
        } finally {
            FreeProviderDiagnostics.clear()
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun demoProviderIsExplicitAndNeverTheOrdinaryDefault() {
        assertTrue(FreeAiProvider.PORCHLM.isDemo)
        assertFalse(FreeAiProvider.default.isDemo)
    }
}
