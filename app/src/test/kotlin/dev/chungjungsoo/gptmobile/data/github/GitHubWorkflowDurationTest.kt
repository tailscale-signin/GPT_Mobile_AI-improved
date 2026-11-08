package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowDurationTest {
    @Test fun reversedSkippedJobTimesRemainRawAndCannotProduceZeroOrNegativeDuration() {
        val raw = Json.parseToJsonElement("""{"id":7,"status":"completed","conclusion":"skipped","started_at":"2026-11-08T19:17:09Z","completed_at":"2026-11-08T19:17:00Z"}""").jsonObject
        val annotated = githubWorkflowTiming(raw)
        assertEquals(raw["started_at"], annotated["started_at"])
        assertEquals(raw["completed_at"], annotated["completed_at"])
        assertEquals(JsonNull, annotated["duration_ms"])
        assertEquals(JsonPrimitive("INCONSISTENT_UPSTREAM_TIMESTAMPS"), annotated["duration_status"])
        val job = liveProcess(annotated)
        assertEquals(-9_000L, requireNotNull(job.completed) - requireNotNull(job.started))
        assertEquals("Duration unavailable · inconsistent upstream timestamps", githubWorkflowDuration(job.started, job.completed, job.status, 0))
    }

    @Test fun elapsedAndCompletedDurationsRequireUsableBounds() {
        assertEquals("Total 00:00:09", githubWorkflowDuration(1_000, 10_000, "success", 20_000))
        assertEquals("00:00:09", githubWorkflowDuration(1_000, null, "in_progress", 10_000))
        assertTrue(githubWorkflowDuration(10_000, null, "in_progress", 1_000).contains("unavailable"))
        assertTrue(githubWorkflowDuration(null, 10_000, "success", 20_000).contains("unavailable"))
        assertTrue(githubWorkflowDuration(1_000, null, "skipped", 20_000).contains("unavailable"))
    }
}
