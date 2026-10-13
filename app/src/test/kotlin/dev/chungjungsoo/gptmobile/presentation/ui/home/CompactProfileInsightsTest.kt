package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.ModelPrice
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProfileInsightsTest {
    private val profile = PlatformV2(uid = "one", name = "Fixture", model = "test")
    private val settings = AppFeatureSettings(newChatPrices = true, newChatScores = true)
    private fun insight(price: ModelPrice? = null) = ProfileInsight(profile, null, false, price, settings, 100_000)

    @Test fun absentAndLoadingNeverBecomeZero() {
        assertEquals(InsightAvailability.MISSING, insight().compact().scoreState)
        assertEquals(InsightAvailability.MISSING, insight().compact().priceState)
        assertEquals(InsightAvailability.LOADING, insight().copy(scoresLoaded = false).compact().scoreState)
        assertNull(insight().compact().rate)
    }

    @Test fun localWorkIsNotARemoteScoreOrFreePrice() {
        val value = insight().copy(profile = profile.copy(compatibleType = ClientType.LITERT_LM)).compact()
        assertEquals(InsightAvailability.ON_DEVICE, value.priceState)
        assertNull(value.score)
    }

    @Test fun preciseSmallAndZeroRatesSurviveAndExpiredDoesNot() {
        val price = ModelPrice("OPENAI", "test", "0.00000001", "0", source = PriceSource.PROVIDER_API, sourceUrl = "https://example.org", validatedAt = 1)
        assertEquals("USD 0.00000001 / 0", insight(price).compact().rate)
        assertNull(insight(price).copy(now = 1_000_000_000).compact().rate)
    }

    @Test fun hiddenMetricsAndChangedContext() {
        assertEquals(InsightAvailability.HIDDEN, insight().copy(settings = settings.copy(newChatPrices = false)).compact().priceState)
        assertFalse(insight().matches(profile.copy(model = "different")))
        assertTrue(insight().matches(profile.copy(name = "renamed")))
    }

    @Test fun minimumHashPaletteIsSafe() {
        // This Java hash is Int.MIN_VALUE; abs(hash) used to remain negative.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        dev.chungjungsoo.gptmobile.presentation.common.getBeveledLabelColors("polygenelubricants")
    }
}
