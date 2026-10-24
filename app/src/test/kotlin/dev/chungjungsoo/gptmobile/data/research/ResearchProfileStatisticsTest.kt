package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchProfileStatisticsTest {
    @Test fun failedAndSkippedStagesStaySeparateFromTransportCompletionAndDuplicateSessions() {
        val root = "1:1"
        val failed = ResearchModelEvent("stage-failed", "EXTRACT", "a", "model", false, "FAILED", 1, invocationId = "request", failureCategory = "TRANSPORT")
        val skipped = ResearchModelEvent("stage-skipped", "EXTRACT", "a", "model", false, "SKIPPED", 2, failureCategory = "ADMISSION", reason = "PROVIDER_COOLDOWN")
        val session = ResearchSession("run-a", 1, ResearchSnapshot("Task", modelEvents = listOf(failed, skipped)), root)
        val request = ModelInvocation("request", "run-a", root, "FREE", "model", "research-extract", 20, 30, status = "COMPLETED", durationMs = 700, profileUid = "a")
        val stats = researchProfileStatistics(root, setOf("a", "b"), listOf(session, session.copy(runId = "shared")), listOf(request, request), emptyList()).associateBy { it.profileUid }
        val a = stats.getValue("a")
        assertEquals(1, a.completedRequests)
        assertEquals(0, a.acceptedStages)
        assertEquals(1, a.failedStages)
        assertEquals(1, a.skippedStages)
        assertEquals("ADMISSION", a.lastFailureCategory)
        assertEquals("request", a.lastInvocationId)
        assertEquals(700L, a.modelDurationMs)
        assertEquals(0, stats.getValue("b").modelRequests)
    }

    @Test fun acceptedProfilesHaveZeroRowsBeforeAnySessionOrRequestExists() {
        val stats = researchProfileStatistics("root", setOf("a", "b"), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("a", "b"), stats.map { it.profileUid })
        assertEquals(listOf(0, 0), stats.map { it.modelRequests })
    }

    @Test fun runningRequestsReportObservedUsageInsteadOfReservedCeilings() {
        val request = ModelInvocation("request", "run", "root", "FREE", "model", "research-extract", 3000, 1500, profileUid = "a", observedInputTokens = 120, observedOutputTokens = 9)
        val stats = researchProfileStatistics("root", setOf("a"), emptyList(), listOf(request), emptyList()).single()
        assertEquals(120L, stats.inputTokens)
        assertEquals(9L, stats.outputTokens)
        assertEquals(1, stats.runningRequests)
    }

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
