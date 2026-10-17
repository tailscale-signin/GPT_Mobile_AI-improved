package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubCheckSummaryTest {
    private fun check(body: String, status: String = """{"state":"pending","total_count":0}""") = gitHubCheckSummary(Json.parseToJsonElement(body).jsonObject, Json.parseToJsonElement(status).jsonObject)

    @Test fun emptyChecksAreNeverGreen() {
        assertEquals("NONE", check("""{"total_count":0,"check_runs":[]}"""))
        assertEquals("UNKNOWN", check("""{"check_runs":[]}"""))
    }

    @Test fun successRequiresCompleteEvidence() {
        val run = """{"status":"completed","conclusion":"success"}"""
        assertEquals("PASSED", check("""{"total_count":1,"check_runs":[$run]}"""))
        assertEquals("UNKNOWN", check("""{"total_count":2,"check_runs":[$run]}"""))
        assertEquals("FAILED", check("""{"total_count":1,"check_runs":[$run]}""", """{"total_count":1,"state":"failure"}"""))
    }

    @Test fun failureTakesPrecedenceOverRunning() {
        assertEquals("FAILED", check("""{"total_count":2,"check_runs":[{"status":"completed","conclusion":"failure"},{"status":"in_progress"}]}"""))
        assertEquals("RUNNING", check("""{"total_count":1,"check_runs":[{"status":"queued"}]}"""))
    }

    @Test fun legacyStatusesAndUnavailablePermissionsAreDistinct() {
        assertEquals("PASSED", check("""{"total_count":0,"check_runs":[]}""", """{"total_count":1,"state":"success"}"""))
        assertEquals("RUNNING", check("""{"total_count":0,"check_runs":[]}""", """{"total_count":1,"state":"pending"}"""))
        assertEquals("UNKNOWN", check("""{"total_count":0,"check_runs":[]}""", "{}"))
    }
}
