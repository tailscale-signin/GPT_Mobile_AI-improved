package dev.chungjungsoo.gptmobile.data.pricing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPriceTest {
    private val now = 1_800_000_000_000L
    private fun price() = ModelPrice("OPENAI", "gpt-4.1", "2", "8", source = PriceSource.PROVIDER_API, sourceUrl = "https://openai.com", validatedAt = now)

    @Test fun tinyPerTokenRatesKeepExactDecimalPrecision() {
        assertEquals("0.15", perMillion("0.00000015"))
        assertEquals("0", perMillion("0"))
        assertNull(perMillion("-0.1"))
        assertNull(perMillion("NaN"))
        assertNull(perMillion("1E-999"))
    }

    @Test fun openRouterRegionsShareBillingIdentityWithoutAdmittingCustomGateways() {
        val profile = PlatformV2(name = "fixture", compatibleType = ClientType.OPENROUTER)
        listOf("openrouter.ai", "eu.openrouter.ai", "us.openrouter.ai").forEach { host ->
            assertEquals("OPENROUTER", billingProvider(profile.copy(apiUrl = "https://$host/api/v1")))
        }
        listOf("https://openrouter.ai.example/api/v1", "http://eu.openrouter.ai/api/v1", "https://eu.openrouter.ai/custom", "https://fixture@us.openrouter.ai/api/v1").forEach { endpoint ->
            assertNull(billingProvider(profile.copy(apiUrl = endpoint)))
        }
    }

    @Test fun apiFreshnessExpiresWithoutPretendingItIsFree() {
        assertEquals(PriceFreshness.FRESH, price().freshness(now + 3_600_000))
        assertEquals(PriceFreshness.STALE, price().freshness(now + 3_600_001))
        assertEquals(PriceFreshness.EXPIRED, price().freshness(now + 72 * 3_600_000 + 1))
        assertEquals(PriceFreshness.EXPIRED, price().freshness(now - 1))
    }

    @Test fun reviewedDatesUseUtcAndIndependentExpiry() {
        val p = price().copy(source = PriceSource.REVIEWED, reviewedOn = "2026-10-12")
        val reviewed = reviewTime(p.reviewedOn)!!
        assertEquals(PriceFreshness.FRESH, p.freshness(reviewed + 7 * 86_400_000))
        assertEquals(PriceFreshness.STALE, p.freshness(reviewed + 8 * 86_400_000))
        assertEquals(PriceFreshness.EXPIRED, p.freshness(reviewed + 31 * 86_400_000))
        assertEquals(PriceFreshness.EXPIRED, p.copy(validUntil = reviewed + 1000).freshness(reviewed + 1000))
        assertNull(reviewTime("not a date"))
    }

    @Test fun compatibleTransportCannotImpersonateBillingProvider() {
        val p = PlatformV2(name = "one", compatibleType = ClientType.OPENAI, apiUrl = "https://api.openai.com/v1")
        assertEquals("OPENAI", billingProvider(p))
        for (url in listOf("https://proxy.example/v1", "https://api.openai.com.example/v1", "http://api.openai.com/v1", "https://user@api.openai.com/v1", "https://api.openai.com/custom", "https://api.openai.com/v1?key=secret")) assertNull(billingProvider(p.copy(apiUrl = url)))
    }

    @Test fun manualPricesCannotBleedAcrossAccountsModelsOrRoutes() {
        val p = PlatformV2(name = "one", providerConnectionUid = "account", model = "exact-model")
        assertNotEquals(priceContextKey(p), priceContextKey(p.copy(providerConnectionUid = "second")))
        assertNotEquals(priceContextKey(p), priceContextKey(p.copy(model = "exact-model-new")))
        assertNotEquals(priceContextKey(p), priceContextKey(p.copy(batchMode = true)))
        assertNotEquals(priceContextKey(p), priceContextKey(p.copy(openRouterRouting = "route")))
        assertEquals(priceContextKey(p), priceContextKey(p.copy(name = "renamed")))
    }

    @Test fun unknownAndInvalidAreDistinctFromAnExplicitZeroRate() {
        assertTrue(price().copy(inputPerMillion = "0", outputPerMillion = "0").valid())
        assertFalse(price().copy(outputPerMillion = "").valid())
        assertFalse(price().copy(currency = "usd").valid())
    }

    @Test fun blankConnectionsRemainIsolatedAndInvalidExtrasAreRejected() {
        val first = PlatformV2(uid = "first", name = "one", providerConnectionUid = "")
        assertNotEquals(priceContextKey(first), priceContextKey(first.copy(uid = "second")))
        assertFalse(price().copy(extras = mapOf("request" to "-1")).valid())
        assertFalse(price().copy(extras = mapOf("request" to "NaN")).valid())
        assertTrue(price().copy(extras = mapOf("request" to "0")).valid())
    }
}
