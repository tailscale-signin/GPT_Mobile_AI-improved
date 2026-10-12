package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchOverhaulTest {
    @Test
    fun effortIsMonotonicAndMaximumDoesNotInheritLegacyTwelvePageCap() {
        val targets = (0..100).map { DeepResearchSettings.targetForEffort(it) }
        assertTrue(targets.zipWithNext().all { it.first <= it.second })
        assertEquals(listOf(0, 30, 90, 180, 300), listOf(0, 25, 50, 75, 100).map { DeepResearchSettings.targetForEffort(it) })
        assertEquals(300, MultiSearchResearchSelection(true, 100).applyTo(ModelDelegationSettings()).deepResearch.maxPages)
        assertEquals(150, DeepResearchSettings(sourceSafetyCap = 150).forEffort(100).maxPages)
    }

    @Test
    fun sourceSnapshotSurvivesOldCacheThresholdsWithoutCountingAbstracts() {
        val sources = (1..300).map { id -> ResearchSource("S$id", "https://example.org/paper/$id", "Paper $id", status = "Read", passage = "Captured body $id", retrievalQuality = "FULL_TEXT") }
        val restored = ResearchSnapshot.parse(ResearchSnapshot("Test", sources = sources, targetSources = 300).json())!!
        assertEquals(300, restored.sources.count { it.readable })
        assertEquals(300, restored.targetSources)
        assertFalse(sources.first().copy(retrievalQuality = "ABSTRACT").readable)
    }

    @Test
    fun cachedAndUndispatchedOutcomesNeverInflateReliabilityAndEventsAreIdempotent() {
        val directory = Files.createTempDirectory("research-telemetry").toFile()
        try {
            val store = ResearchTelemetryStore(directory)
            val first = ResearchAttempt("one", "crossref", "search", 1, outcome = "SUCCESS")
            store.record(first)
            store.record(first)
            store.record(first.copy(eventId = "failure", outcome = "FAILED"))
            store.record(first.copy(eventId = "cache", cacheHit = true))
            store.record(first.copy(eventId = "denial", outcome = "DENIED", dispatched = false))
            val stats = ResearchTelemetryStore(directory).rollup("crossref")
            assertEquals(2, stats.attempts)
            assertEquals(1, stats.successes)
            assertEquals(1, stats.cached)
            assertEquals(1, stats.skipped)
            assertEquals(50.0, stats.successPercent!!, 0.01)
        } finally {
            directory.deleteRecursively()
        }
    }
}
