package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDelegationSettingsTest {
    @Test
    fun normalizedClampsExtendedDelegationControls() {
        val normalized = ModelDelegationSettings(
            timeoutSeconds = 999,
            maxCallsPerTurn = 99,
            maxLocalModelCalls = 99,
            maxSearchQueries = 99,
            searchResultsPerEngine = 99,
            maxPages = 99,
            crawlDepth = 99,
            pageFetchConcurrency = 99,
            maxPageCharacters = 999999,
            handoffTokens = 999999,
            compactionThresholdCharacters = 999999
        ).normalized()

        assertEquals(600, normalized.timeoutSeconds)
        assertEquals(16, normalized.maxCallsPerTurn)
        assertEquals(48, normalized.maxLocalModelCalls)
        assertEquals(20, normalized.maxSearchQueries)
        assertEquals(28, normalized.searchResultsPerEngine)
        assertEquals(32, normalized.maxPages)
        assertEquals(8, normalized.crawlDepth)
        assertEquals(16, normalized.pageFetchConcurrency)
        assertEquals(96000, normalized.maxPageCharacters)
        assertEquals(8192, normalized.handoffTokens)
        assertEquals(48000, normalized.compactionThresholdCharacters)
        assertEquals(1, normalized.localRetryLimit)
        assertEquals(50, normalized.lowBatteryThresholdPercent)
        assertEquals(4096, normalized.remoteSynthesisOutputTokens)
    }

    @Test
    fun defaultsKeepResearchScopedAndHandoffCompact() {
        val defaults = ModelDelegationSettings()
        assertEquals(10, defaults.maxSearchQueries)
        assertEquals(14, defaults.searchResultsPerEngine)
        assertEquals(16, defaults.maxPages)
        assertEquals(4, defaults.crawlDepth)
        assertEquals(8, defaults.pageFetchConcurrency)
        assertEquals(48000, defaults.maxPageCharacters)
        assertEquals(256, defaults.handoffTokens)
        assertEquals(256, defaults.compactionThresholdCharacters)
        assertEquals(0, defaults.localRetryLimit)
        assertEquals(20, defaults.lowBatteryThresholdPercent)
        assertEquals(3072, defaults.remoteSynthesisOutputTokens)
    }
}
