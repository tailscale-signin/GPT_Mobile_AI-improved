package dev.chungjungsoo.gptmobile.data.pricing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePriceResolverTest {
    private val now = requireNotNull(reviewTime("2026-11-22")) + 1_000
    private val profile = PlatformV2(uid = "profile", name = "Profile", model = "model")
    private val account = ModelPrice("OPENAI", "model", "1", "2", source = PriceSource.ACCOUNT_OVERRIDE, sourceUrl = "Account contract", reviewedOn = "2026-11-22", validUntil = now + 86_400_000)
    private val catalog = account.copy(source = PriceSource.REVIEWED, inputPerMillion = "3")

    @Test fun currentAccountOverrideTakesPrecedenceAndRenamingDoesNotInvalidateIt() {
        val saved = ManualPrice(priceContextKey(profile), account)
        assertEquals(account, resolveProfilePrice(profile.copy(name = "Renamed"), listOf(saved), listOf(catalog), now))
    }

    @Test fun customEndpointsNeedAnExactCustomAccountOverride() {
        val custom = profile.copy(apiUrl = "https://private.example/deployment/v1")
        val accountRate = account.copy(provider = "CUSTOM")
        assertNull(resolveProfilePrice(custom, emptyList(), listOf(catalog), now))
        assertEquals(accountRate, resolveProfilePrice(custom, listOf(ManualPrice(priceContextKey(custom), accountRate)), listOf(catalog), now))
        assertNull(resolveProfilePrice(custom.copy(apiUrl = "https://private.example/another/v1"), listOf(ManualPrice(priceContextKey(custom), accountRate)), listOf(catalog), now))
    }

    @Test fun malformedOrExpiredOverridesCannotRelabelARateForAnotherProviderOrModel() {
        listOf(account.copy(provider = "CUSTOM"), account.copy(model = "different"), account.copy(validUntil = now), account.copy(source = PriceSource.REVIEWED)).forEach { invalid ->
            assertNull(resolveProfilePrice(profile, listOf(ManualPrice(priceContextKey(profile), invalid)), emptyList(), now))
        }
    }

    @Test fun catalogCannotSupplyUnscopedAccountOverridesOrInteractiveRatesForBatchMode() {
        assertNull(resolveProfilePrice(profile, emptyList(), listOf(account), now))
        assertNull(resolveProfilePrice(profile.copy(batchMode = true), emptyList(), listOf(catalog), now))
        assertNull(resolveProfilePrice(profile.copy(model = "model:batch"), emptyList(), listOf(catalog.copy(model = "model:batch")), now))
    }
}
