package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FreeScopedHealthTest {
    @Before fun before() = FreeCatalogState.resetForTest()

    @After fun after() = FreeCatalogState.resetForTest()

    @Test fun repeatedHttp502OpensOnlyTheFailingRouteUntilRecoverySucceeds() {
        val provider = FreeAiProvider.VIREONIX
        FreeCatalogState.recordHttpFailure(provider, 502, "Temporarily unavailable", model = "auto", credentialIdentity = "key-a")
        assertNull(FreeCatalogState.generationUnavailableReason(provider, "auto", "key-a"))
        FreeCatalogState.recordHttpFailure(provider, 502, "Temporarily unavailable", model = "auto", credentialIdentity = "key-a")
        assertNotNull(FreeCatalogState.generationUnavailableReason(provider, "auto", "key-a"))
        assertNull(FreeCatalogState.generationUnavailableReason(provider, "other-model", "key-a"))
        assertNull(FreeCatalogState.generationUnavailableReason(provider, "auto", "key-b"))
        FreeCatalogState.recordResponse(provider, "auto", credentialIdentity = "key-a")
        assertNull(FreeCatalogState.generationUnavailableReason(provider, "auto", "key-a"))
    }

    @Test fun upstreamRetryDeadlineIsNotShortenedByTheLocalCircuitBreaker() {
        val deadline = System.currentTimeMillis() + 3_600_000
        repeat(2) { FreeCatalogState.recordHttpFailure(FreeAiProvider.VIREONIX, 502, "Temporarily unavailable", retryAt = deadline, model = "auto") }
        assertEquals(deadline, FreeCatalogState.generationStatus(FreeAiProvider.VIREONIX, "auto")?.retryAt)
    }

    @Test fun anotherModelSuccessDoesNotClearTheFailingModel() {
        repeat(2) { FreeCatalogState.recordTerminalFailure(FreeAiProvider.LLM7, "model-a", "same failure", credentialIdentity = "key-a") }
        FreeCatalogState.recordResponse(FreeAiProvider.LLM7, "model-b", credentialIdentity = "key-a")
        assertNotNull(FreeCatalogState.generationUnavailableReason(FreeAiProvider.LLM7, "model-a", "key-a"))
        assertNull(FreeCatalogState.generationUnavailableReason(FreeAiProvider.LLM7, "model-b", "key-a"))
    }

    @Test fun credentialDenialsDoNotDisableAnotherCredentialOrGetClearedByItsSuccess() {
        repeat(2) { FreeCatalogState.recordFailure(FreeAiProvider.LLM7, "denied", FreeGenerationReadiness.ACCESS_DENIED, model = "model", credentialIdentity = "key-a") }
        assertNull(FreeCatalogState.generationUnavailableReason(FreeAiProvider.LLM7, "model", "key-b"))
        FreeCatalogState.recordResponse(FreeAiProvider.LLM7, "model", credentialIdentity = "key-b")
        assertNotNull(FreeCatalogState.generationUnavailableReason(FreeAiProvider.LLM7, "model", "key-a"))
    }

    @Test fun resolvedAliasOnlySettlesTheRequestedRoute() {
        repeat(2) { FreeCatalogState.recordTerminalFailure(FreeAiProvider.LLM7, "automatic", "failed", credentialIdentity = "key-a") }
        FreeCatalogState.recordResponse(FreeAiProvider.LLM7, "served-model", credentialIdentity = "key-a", requestedModel = "automatic")
        assertNull(FreeCatalogState.generationUnavailableReason(FreeAiProvider.LLM7, "automatic", "key-a"))
        assertEquals("served-model", FreeCatalogState.generationStatus(FreeAiProvider.LLM7, "automatic", "key-a")?.servedModel)
    }
}
