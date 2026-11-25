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

    @Test fun aggregateUsesEachNamedStatusBeforeReleasingTheSavedDiagnosis() {
        val runs = Json.parseToJsonElement("""{"total_count":0,"check_runs":[]}""").jsonObject
        fun summary(row: String) = gitHubCheckSummary(runs, Json.parseToJsonElement("""{"total_count":1,"state":"success","statuses":[$row]}""").jsonObject, waitForAll = true)
        assertEquals("FAILED", summary("""{"context":"build","state":"failure"}"""))
        assertEquals("RUNNING", summary("""{"context":"build","state":"pending"}"""))
        assertEquals("UNKNOWN", summary("""{"state":"success"}"""))
        assertEquals("UNKNOWN", summary("""{"context":"build","state":"unknown"}"""))
        assertEquals("PASSED", summary("""{"context":"build","state":"success"}"""))
    }

    @Test fun wholeCommitKeepsDistinctAndUnidentifiedCheckSuites() {
        fun inSuite(row: String, suite: Long) = row.dropLast(1) + ""","check_suite":{"id":$suite}}"""
        fun summary(older: String, newer: String) = gitHubCheckSummary(
            Json.parseToJsonElement("""{"total_count":2,"check_runs":[$older,$newer]}""").jsonObject,
            Json.parseToJsonElement("""{"total_count":0,"statuses":[]}""").jsonObject,
            waitForAll = true
        )
        val newest = inSuite(attempt(20, "success"), 200)
        assertEquals("RUNNING", summary(inSuite(attempt(10, null, "in_progress"), 100), newest))
        assertEquals("FAILED", summary(inSuite(attempt(10, "failure"), 100), newest))
        assertEquals("RUNNING", summary(attempt(10, null, "in_progress"), attempt(20, "success")))
        assertEquals("FAILED", summary(attempt(10, "failure"), newest))
        assertEquals("FAILED", summary(inSuite(attempt(10, "failure"), 0), newest))
    }

    @Test fun wholeCommitDoesNotInferSameSuiteSupersession() {
        fun inSuite(row: String) = row.dropLast(1) + ""","check_suite":{"id":100}}"""
        fun summary(older: String, newer: String) = gitHubCheckSummary(
            Json.parseToJsonElement("""{"total_count":2,"check_runs":[$older,$newer]}""").jsonObject,
            Json.parseToJsonElement("""{"total_count":0,"statuses":[]}""").jsonObject,
            waitForAll = true
        )
        assertEquals("FAILED", summary(inSuite(attempt(10, "cancelled")), inSuite(attempt(20, "success"))))
        assertEquals("RUNNING", summary(inSuite(attempt(10, "failure")), inSuite(attempt(20, null, "in_progress"))))
        assertEquals("FAILED", summary(inSuite(attempt(10, "failure", app = 99)), inSuite(attempt(20, "success"))))
    }

    private fun withSuite(run: String, suite: String): String = run.dropLast(1) + ""","check_suite":$suite}"""

    private fun aggregate(vararg rows: String): String = gitHubCheckSummary(
        Json.parseToJsonElement("""{"total_count":${rows.size},"check_runs":[${rows.joinToString(",")}]}""").jsonObject,
        Json.parseToJsonElement("""{"state":"pending","total_count":0,"statuses":[]}""").jsonObject,
        waitForAll = true
    )

    @Test fun wholeCommitChecksKeepEveryReturnedPendingOrFailedRow() {
        val pending = withSuite(attempt(1, null, "in_progress"), """{"id":101}""")
        val failed = withSuite(attempt(1, "failure"), """{"id":101}""")
        for (suite in listOf("""{"id":101}""", """{"id":102}""")) {
            val passed = withSuite(attempt(2, "success"), suite)
            assertEquals("RUNNING", aggregate(pending, passed))
            assertEquals("RUNNING", aggregate(passed, pending))
            assertEquals("FAILED", aggregate(failed, passed))
            assertEquals("FAILED", aggregate(passed, failed))
            // Ordinary summaries retain their existing rerun behavior.
            assertEquals("PASSED", check("""{"total_count":2,"check_runs":[$pending,$passed]}"""))
            assertEquals("PASSED", check("""{"total_count":2,"check_runs":[$failed,$passed]}"""))
        }
    }

    @Test fun wholeCommitChecksTrustTheReturnedLatestSelectionWithoutInventingRetries() {
        val passed = withSuite(attempt(2, "success"), """{"id":101}""")
        assertEquals("PASSED", aggregate(passed))
        assertEquals("FAILED", aggregate(withSuite(attempt(1, "cancelled"), """{"id":101}"""), passed))
        assertEquals("FAILED", aggregate(withSuite(attempt(1, "failure"), """{"id":101}"""), passed))
        assertEquals("RUNNING", aggregate(withSuite(attempt(1, null, "in_progress"), """{"id":101}"""), passed))
        assertEquals("UNKNOWN", aggregate(withSuite(attempt(2, null), """{"id":101}""")))
    }

    @Test fun wholeCommitChecksKeepUnknownIdentityAndDuplicateEvidenceConservative() {
        for (suite in listOf("null", "{}", """{"id":0}""", """{"id":-1}""", """{"id":"101"}""", """{"id":{}}""")) {
            val passed = withSuite(attempt(2, "success"), suite)
            assertEquals("RUNNING", aggregate(withSuite(attempt(1, null, "in_progress"), suite), passed))
            assertEquals("FAILED", aggregate(withSuite(attempt(1, "failure"), suite), passed))
        }
        val passed = attempt(2, "success")
        assertEquals("RUNNING", aggregate(attempt(1, null, "in_progress"), passed))
        assertEquals("FAILED", aggregate(attempt(1, "failure"), passed))
        assertEquals("UNKNOWN", aggregate(passed, passed))
        assertEquals("UNKNOWN", aggregate(attempt(0, "success"), passed))
        assertEquals("UNKNOWN", aggregate("""{"status":"completed","conclusion":"success"}"""))
    }
}
