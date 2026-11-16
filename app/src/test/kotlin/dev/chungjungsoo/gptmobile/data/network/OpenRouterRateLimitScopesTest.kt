package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRateLimitScopesTest {
    private fun config(model: String) = ProviderRequestConfig(
        "https://openrouter.ai/api/v1", "test-token", providerType = "OPENROUTER", selectedModel = model
    )

    @Test fun unrelatedPaidModelsDoNotShareUpstreamCooldowns() {
        val first = config("vendor/a")
        val second = config("vendor/b")
        assertNotEquals(OpenRouterRateLimitScopes.identity(first), OpenRouterRateLimitScopes.identity(second))
        assertFalse(OpenRouterRateLimitScopes.admissionIdentities(second).contains(OpenRouterRateLimitScopes.identity(first)))
        assertEquals(OpenRouterRateLimitScopes.identity(first),
            OpenRouterRateLimitScopes.identity(first, "upstream_provider_shared_pool"))
    }

    @Test fun verifiedFreeTierQuotaIsSharedOnlyByFreeModels() {
        val a = config("vendor/a:free")
        val b = config("vendor/b:free")
        val paid = config("vendor/a")
        val shared = OpenRouterRateLimitScopes.identity(a, "openrouter_free_tier_per_minute")
        assertEquals(shared, OpenRouterRateLimitScopes.identity(b, "openrouter_free_tier_daily"))
        assertTrue(OpenRouterRateLimitScopes.admissionIdentities(a).contains(shared))
        assertTrue(OpenRouterRateLimitScopes.admissionIdentities(b).contains(shared))
        assertFalse(OpenRouterRateLimitScopes.admissionIdentities(paid).contains(shared))
    }

    @Test fun unknown429RemainsModelSpecific() {
        val a = config("vendor/a:free")
        assertEquals(OpenRouterRateLimitScopes.identity(a),
            OpenRouterRateLimitScopes.identity(a, "unrecognized_future_value"))
        assertNotEquals(OpenRouterRateLimitScopes.identity(a),
            OpenRouterRateLimitScopes.identity(a, "openrouter_free_tier_per_minute"))
    }

    @Test fun parsesTypedErrorMetadataNotFreeformMessage() {
        assertEquals("openrouter_free_tier_per_minute", OpenRouterRateLimitScopes.sourceFromBody(
            """{"error":{"code":429,"metadata":{"limit_source":"openrouter_free_tier_per_minute"}}}"""
        ))
        assertEquals("upstream_provider_shared_pool", OpenRouterRateLimitScopes.sourceFromBody(
            """{"error":{"metadata":{"limit_source":"upstream_provider_shared_pool"}}}"""
        ))
        assertNull(OpenRouterRateLimitScopes.sourceFromBody("""{"error":{"message":"openrouter_free_tier_per_minute"}}"""))
    }

    @Test fun otherProvidersRetainCredentialScope() {
        val a = ProviderRequestConfig("https://api.groq.com/v1", "same-key", selectedModel = "a")
        assertEquals(OpenRouterRateLimitScopes.identity(a),
            OpenRouterRateLimitScopes.identity(a.copy(selectedModel = "b")))
    }
}
