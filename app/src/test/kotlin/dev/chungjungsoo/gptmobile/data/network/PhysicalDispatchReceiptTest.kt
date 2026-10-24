package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalDispatchReceiptTest {
    private val config = ProviderRequestConfig("https://fixture.invalid/v1", null, providerType = "CUSTOM")
    private val body = """{"model":"test","messages":[{"role":"user","content":"Transport fixture request."}],"max_tokens":128}"""

    @Test fun revokedSourceCannotChargeAttemptOrPublishAnInclusionReceipt() = runBlocking {
        val recorder = RequestDispatchContext(emptySet())
        var attempts = 0
        val result = runCatching {
            withContext(recorder + PhysicalAttemptBoundary { attempts++ } + RequestAuthorizationGuard("revision-1") { error("revoked") }) {
                ModelRequestPreparation(mockk()).prepare(body, config, "test", 128)
            }
        }
        assertTrue(result.isFailure)
        assertEquals(0, attempts)
        assertTrue(recorder.receipts.isEmpty())
    }

    @Test fun revocationBetweenPreparationAndDispatchPreventsCharging() = runBlocking {
        val recorder = RequestDispatchContext(emptySet())
        var checks = 0
        var attempts = 0
        val result = runCatching {
            withContext(recorder + PhysicalAttemptBoundary { attempts++ } + RequestAuthorizationGuard("revision") { check(++checks == 1) }) {
                ModelRequestPreparation(mockk()).prepare(body, config, "test", 128)
            }
        }
        assertTrue(result.isFailure)
        assertEquals(2, checks)
        assertEquals(0, attempts)
        assertTrue(recorder.receipts.isEmpty())
    }

    @Test fun retriesHaveDistinctPhysicalIdsAndTheValidatedPermissionRevision() = runBlocking {
        val recorder = RequestDispatchContext(emptySet())
        val attempts = mutableListOf<String>()
        var checks = 0
        withContext(recorder + PhysicalAttemptBoundary { attempts += it } + RequestAuthorizationGuard("revision-1") { checks++ }) {
            val preparation = ModelRequestPreparation(mockk())
            repeat(2) { preparation.prepare(body, config, "test", 128) }
        }
        assertEquals(4, checks)
        assertEquals(attempts, recorder.receipts.map { it.physicalAttemptId })
        assertNotEquals(attempts[0], attempts[1])
        assertTrue(recorder.receipts.all { it.permissionRevision == "revision-1" })
    }

    @Test fun classicPostReservesItsRealFixedOutputAllowance() = runBlocking {
        val recorder = RequestDispatchContext(emptySet())
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS
        val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, providerType = "FREE")
        withContext(recorder) {
            val payload = body.replace("128", "9000").replace("test", provider.model)
            val wire = ModelRequestPreparation(mockk()).prepare(payload, config, provider.model, null)
            assertTrue(wire.contains("\"max_tokens\":1500"))
        }
        val receipt = recorder.receipts.single()
        assertEquals("pollinations-classic-post-v1", receipt.serializerRevision)
        assertEquals(1500L, receipt.outputTokens)
        assertTrue(!receipt.capacityChecked)
        assertTrue(receipt.physicalAttemptId != null)
    }

    @Test fun classicRejectsSmallerHardCapsAndContextHeadroomBeforeCharging() = runBlocking {
        var attempts = 0
        val recorder = RequestDispatchContext(emptySet())
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS
        val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, providerType = "FREE")
        withContext(recorder + PhysicalAttemptBoundary { attempts++ }) {
            assertTrue(runCatching { ModelRequestPreparation(mockk()).prepare(body, config, provider.model, 128) }.isFailure)
            val bounded = body.replace("128", "1500")
            assertTrue(runCatching { ModelRequestPreparation(mockk()).prepare(bounded, config.copy(contextCeiling = 1000), provider.model, 1500) }.isFailure)
            assertTrue(runCatching { ModelRequestPreparation(mockk()).prepare(bounded, config.copy(requireKnownCapacity = true), provider.model, 1500) }.isFailure)
            withContext(RequestAuthorizationGuard("revoked") { error("revoked") }) {
                assertTrue(runCatching { ModelRequestPreparation(mockk()).prepare(bounded, config, provider.model, 1500) }.isFailure)
            }
        }
        assertEquals(0, attempts)
        assertTrue(recorder.receipts.isEmpty())
    }
}
