package dev.chungjungsoo.gptmobile.data.github

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubLiveProcessTest {
    @Test fun comparisonKeepsPartialInventoriesAndDuplicateNamesExplicit() {
        fun job(id: String, status: String) = GitHubLiveProcess(id, "Unit tests", status, null, null)
        val result = compareGitHubAttempts(listOf(job("new", "success")), listOf(job("old", "failure")), false)
        org.junit.Assert.assertTrue(result.contains("failure → success"))
        org.junit.Assert.assertTrue(result.contains("incomplete inventory"))
        val duplicate = compareGitHubAttempts(listOf(job("a", "success"), job("b", "failure")), listOf(job("old", "success")), true)
        org.junit.Assert.assertTrue(duplicate.contains("ambiguous repeated name"))
    }

    @Test fun runningJobsExposeActualCurrentStepAndStartTime() {
        val value = Json.parseToJsonElement("""{"id":42,"name":"Android tests","status":"in_progress","started_at":"2026-11-02T20:00:00Z","steps":[{"name":"Setup","status":"completed"},{"name":"Test","status":"in_progress"}]}""").jsonObject
        val result = liveProcess(value, "job:")
        assertEquals("job:42", result.id)
        assertEquals("in_progress", result.status)
        assertEquals("1/2 steps · Test", result.steps)
        assertEquals(Instant.parse("2026-11-02T20:00:00Z").toEpochMilli(), result.started)
        assertNull(result.completed)
    }

    @Test fun completedRunNeverUsesLastUpdatedAsInventedCompletionTime() {
        val result = liveProcess(Json.parseToJsonElement("""{"id":1,"status":"completed","conclusion":"success","updated_at":"2026-11-02T20:00:00Z"}""").jsonObject)
        assertEquals("success", result.status)
        assertNull(result.completed)
    }

    @Test fun missingConclusionIsUnknownAndMalformedTimeIsAbsent() {
        val result = liveProcess(Json.parseToJsonElement("""{"id":1,"status":"completed","started_at":"invalid"}""").jsonObject)
        assertEquals("unknown", result.status)
        assertNull(result.started)
    }
}
