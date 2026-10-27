package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubCheckSummaryTest {
    private fun check(body: String, status: String = """{"state":"pending","total_count":0}""") = gitHubCheckSummary(Json.parseToJsonElement(body).jsonObject, Json.parseToJsonElement(status).jsonObject)

    private fun attempt(id: Long, conclusion: String?, state: String = "completed", app: Long = 15368) = """{"id":$id,"name":"Android validation","app":{"id":$app},"status":"$state","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun rerunSupersedesAnOlderCancellationOnlyForTheSameCheckAndApp() {
        val cancelled = attempt(122485881782, "cancelled")
        val running = attempt(122494824012, null, "in_progress")
        val passed = attempt(122494824012, "success")
        assertEquals("RUNNING", check("""{"total_count":2,"check_runs":[$running,$cancelled]}"""))
        assertEquals("PASSED", check("""{"total_count":2,"check_runs":[$passed,$cancelled]}"""))
        assertEquals("FAILED", check("""{"total_count":2,"check_runs":[${attempt(122494824012, "failure")},${attempt(122485881782, "success") }]}"""))
        assertEquals("FAILED", check("""{"total_count":2,"check_runs":[$passed,${attempt(122485881782, "failure", app = 99)}]}"""))
    }

    @Test fun duplicatedOrIncompleteRerunEvidenceCannotBeGreen() {
        val passed = attempt(2, "success")
        assertEquals("UNKNOWN", check("""{"total_count":2,"check_runs":[$passed,$passed]}"""))
        assertEquals("UNKNOWN", check("""{"total_count":3,"check_runs":[$passed,${attempt(1, "cancelled") }]}"""))
        assertEquals("UNKNOWN", check("""{"total_count":2,"check_runs":[${attempt(0, "success")},${attempt(1, "success")}]}"""))
    }

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
