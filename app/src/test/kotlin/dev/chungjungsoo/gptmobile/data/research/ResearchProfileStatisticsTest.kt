package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchProfileStatisticsTest {
    @Test fun sharedAndCanonicalSourcesAreNotCountedAsUniqueFindsForEveryProfile() {
        val first = ResearchSource("S1", "https://example.org/a", "A", discoveredByProfiles = setOf("a"))
        val shared = first.copy(id = "S2", discoveredByProfiles = setOf("b"))
        val exclusive = ResearchSource("S3", "https://example.org/c", "C", discoveredByProfiles = setOf("a"))
        val legacy = ResearchSource("S4", "https://example.org/old", "Legacy")
        val rows = listOf(ResearchSession("run-a", 1, ResearchSnapshot("Task", sources = listOf(first, exclusive, legacy)), "1:1"), ResearchSession("run-b", 1, ResearchSnapshot("Task", sources = listOf(shared, exclusive)), "1:1"))
        val spend = ModelInvocation("i", "run-a", "1:1", "test", "m", "research-appraisal", 20, 30, estimated = false, status = "COMPLETED", profileUid = "a")
        val stats = researchProfileStatistics("1:1", setOf("a", "b", "c", "d", "e"), rows, listOf(spend, spend, spend.copy(id = "other", turnKey = "1:2")), emptyList()).associateBy { it.profileUid }
        assertEquals(5, stats.size)
        assertEquals(2, stats.getValue("a").sources)
        assertEquals(1, stats.getValue("a").uniqueSources)
        assertEquals(1, stats.getValue("b").sources)
        assertEquals(0, stats.getValue("b").uniqueSources)
        assertEquals(50L, stats.getValue("a").let { it.inputTokens + it.outputTokens })
        assertEquals(0, stats.getValue("c").sources)
        assertEquals(first.discoveredByProfiles, ResearchSnapshot.parse(ResearchSnapshot("Task", sources = listOf(first)).json())!!.sources.single().discoveredByProfiles)
    }
}
