package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import dev.chungjungsoo.gptmobile.data.network.FreeCooldown
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.HttpURLConnection
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreeProviderAccessTest {
    @Before fun reset() {
        FreeCatalogState.resetForTest()
    }

    @Test fun catalogAcceptanceDoesNotCloseTheCircuitAndOnlyOneRecoveryCanRun() {
        val provider = FreeAiProvider.HUGGING_FACE
        repeat(2) { FreeCatalogState.recordTerminalFailure(provider, provider.model, "backend choices failed") }
        assertNotNull(FreeCatalogState.generationUnavailableReason(provider, provider.model))
        FreeCatalogState.recordAccepted(provider)
        assertNotNull(FreeCatalogState.generationUnavailableReason(provider, provider.model))
        assertEquals(2, FreeCatalogState.status.value[provider]!!.generationFailures)
        // A later manual attempt may probe after the cooldown; queue acceptance is insufficient.
        FreeCatalogState.recordTerminalFailure(provider, provider.model, "backend choices failed", System.currentTimeMillis() - 400_000)
        assertTrue(FreeCatalogState.beginGeneration(provider, provider.model))
        assertThrows(IllegalStateException::class.java) { FreeCatalogState.beginGeneration(provider, provider.model) }
        FreeCatalogState.endGeneration(provider)
        assertTrue(FreeCatalogState.beginGeneration(provider, provider.model))
        FreeCatalogState.recordResponse(provider, provider.model)
        FreeCatalogState.endGeneration(provider)
        assertNull(FreeCatalogState.generationUnavailableReason(provider, provider.model))
        assertEquals(0, FreeCatalogState.status.value[provider]!!.generationFailures)
    }

    @Test fun llm7UsesAllowanceMetadataAndExcludesPaidOrUnknownModelsEvenWithZeroPrices() {
        val models = FreeCatalogState.decodeModels(
            FreeAiProvider.LLM7,
            Json.parseToJsonElement(
                """{"data":[
          {"id":"free-turbo","model_type":"chat","tier":"turbo","usage_based_only":false,"stream":false,"context_window":{"tokens":8192,"chars":null},"pricing":{"input":0.1,"output":0.2}},
          {"id":"paid-turbo","model_type":"chat","tier":"turbo","usage_based_only":true,"pricing":{"input":0,"output":0}},
          {"id":"pro-only","model_type":"chat","tier":"pro","usage_based_only":false},
          {"id":"unknown","model_type":"chat","tier":"turbo"},
          {"id":"video","model_type":"video","tier":"turbo","usage_based_only":false},
                  {"id":"unknown-tier","model_type":"chat","usage_based_only":false}
        ]}"""
            )
        )
        assertEquals(listOf("free-turbo"), models.map { it.id })
        assertTrue(models.single().freeAllowance)
        assertFalse(models.single().streaming)
        assertEquals(8192, models.single().contextTokens)
    }

    @Test fun catalogConnectionUsesLlm7BearerWithoutSendingAnonymousProviderCredentials() {
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } returns 200
        every { connection.inputStream } answers { """{"data":[]}""".byteInputStream() }
        FreeCatalogState.readCatalog(FreeAiProvider.LLM7, "synthetic-token") { url ->
            assertEquals("https://api.llm7.io/v1/models", url.toString())
            connection
        }
        verify(exactly = 1) { connection.setRequestProperty("Authorization", "Bearer synthetic-token") }
        verify { connection.instanceFollowRedirects = false }
        verify { connection.disconnect() }
        val noToken = mockk<HttpURLConnection>(relaxed = true)
        every { noToken.responseCode } returns 200
        every { noToken.inputStream } answers { """{"data":[]}""".byteInputStream() }
        FreeCatalogState.readCatalog(FreeAiProvider.LLM7, "") { noToken }
        verify(exactly = 0) { noToken.setRequestProperty("Authorization", any()) }
        val anonymous = mockk<HttpURLConnection>(relaxed = true)
        every { anonymous.responseCode } returns 200
        every { anonymous.inputStream } answers { """{"data":[]}""".byteInputStream() }
        FreeCatalogState.readCatalog(FreeAiProvider.KILO, "do-not-send") { anonymous }
        verify(exactly = 0) { anonymous.setRequestProperty("Authorization", any()) }
    }

    @Test fun automaticLlm7SelectionNeedsExplicitCurrentFreeAllowanceMetadata() {
        val selection = dev.chungjungsoo.gptmobile.data.network.Llm7FreeModelSelection
        assertThrows(IllegalStateException::class.java) { selection.select("automatic", FreeModelCatalog.defaults(FreeAiProvider.LLM7)) }
        val catalog = listOf(FreeModelOption("paid"), FreeModelOption("eligible", freeAllowance = true))
        assertEquals("eligible", selection.select("automatic", catalog).id)
        assertThrows(IllegalStateException::class.java) { selection.select("paid", catalog) }
        assertThrows(IllegalStateException::class.java) { selection.select("unknown", catalog) }
    }

    @Test fun parallelCatalogChecksReturnTheirOwnImmutableEligibilitySnapshot() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondRead = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        fun catalog(id: String) = Json.parseToJsonElement("""{"data":[{"id":"$id","model_type":"chat","tier":"turbo","usage_based_only":false}]}""")
        val first = async {
            FreeCatalogState.refresh(FreeAiProvider.LLM7, true, "first-token") {
                firstRead.complete(Unit)
                releaseFirst.await()
                catalog("first-free")
            }
        }
        firstRead.await()
        val second = async {
            FreeCatalogState.refresh(FreeAiProvider.LLM7, true, "second-token") {
                secondRead.complete(Unit)
                releaseSecond.await()
                catalog("second-free")
            }
        }
        releaseFirst.complete(Unit)
        secondRead.await()
        val firstResult = first.await()
        assertFalse(FreeCatalogState.status.value.getValue(FreeAiProvider.LLM7).catalogCheckSucceeded)
        assertTrue(firstResult.succeeded)
        assertEquals("first-free", dev.chungjungsoo.gptmobile.data.network.Llm7FreeModelSelection.select("automatic", firstResult.models).id)
        releaseSecond.complete(Unit)
        assertEquals("second-free", second.await().models.single().id)
        assertEquals("first-free", firstResult.models.single().id)
    }

    @Test fun refreshedSnapshotsKeepLearnedCapacityWithoutAuthorizingRemovedModels() = runTest {
        val provider = FreeAiProvider.LLM7
        fun catalog(context: Int, output: Int, paid: Boolean = false) = Json.parseToJsonElement("""{"data":[{"id":"eligible","model_type":"chat","tier":"turbo","usage_based_only":$paid,"context_window":{"tokens":$context},"max_output_tokens":$output}]}""")
        val first = FreeCatalogState.refresh(provider, true) { catalog(8192, 2048) }
        FreeCatalogState.learnContextLimit(provider, "eligible", 4096)
        val refreshed = FreeCatalogState.refresh(provider, true) { catalog(32768, 8192) }
        assertEquals(8192, first.models.single().contextTokens)
        assertEquals(4096, refreshed.models.single().contextTokens)
        assertEquals(2048, refreshed.models.single().outputTokens)
        val removed = FreeCatalogState.refresh(provider, true) { catalog(32768, 8192, paid = true) }
        assertTrue(removed.succeeded)
        assertTrue(removed.models.isEmpty())
    }

    @Test fun structuredCooldownRetainsEvidenceAndLegacyDeadlinesKeepUnknownScope() {
        val file = Files.createTempDirectory("structured-cooldown").resolve("state.json").toFile()
        var now = 10_000L
        val provider = FreeAiProvider.LLM7
        try {
            val limiter = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            limiter.defer(provider, "3600", credentialIdentity = "credential-a", evidence = FreeCooldown(0, status = 429, providerCode = "quota_exhausted", category = "BUDGET", message = "Token allowance reached", quotaScope = "token", originatingFailureId = "failure-1"))
            val restarted = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            val restored = restarted.savedCooldown(provider, credentialIdentity = "credential-a")!!
            assertEquals(3_610_000L, restored.retryAt)
            assertEquals(10_000L, restored.observedAt)
            assertEquals(429, restored.status)
            assertEquals("failure-1", restored.originatingFailureId)
            assertEquals("retry_after_seconds", restored.deadlineSource)
            assertNull(restarted.savedCooldown(provider, credentialIdentity = "credential-b"))
            file.writeText("""{"LLM7|":3610000}""")
            val legacy = FreeAiRequestLimiter { now }.also { it.initialize(file) }
            assertEquals("legacy_unknown", legacy.savedCooldown(provider, credentialIdentity = "credential-b")!!.deadlineSource)
            assertEquals("unknown", legacy.savedCooldown(provider)!!.quotaScope)
            now = 3_610_000L
            assertNull(legacy.savedCooldown(provider))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }
}
