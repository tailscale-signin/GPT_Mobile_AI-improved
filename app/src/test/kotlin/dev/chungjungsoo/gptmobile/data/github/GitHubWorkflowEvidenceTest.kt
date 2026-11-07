package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowEvidenceTest {
    private val sha = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun runAndJobValidationAcceptsBothSupportedIdEncodings() {
        for (runId in listOf("7", "\"7\"")) {
            for (jobId in listOf("9", "\"9\"")) {
                val args = json("""{"run_id":$runId,"job_id":$jobId,"expected_head_sha":"${sha.uppercase()}"}""")
                GitHubWorkflowEvidence.validateRun(json("""{"id":7,"head_sha":"$sha"}"""), args)
                GitHubWorkflowEvidence.validateJob(json("""{"id":9,"run_id":7,"head_sha":"$sha"}"""), args)
            }
        }
    }

    @Test fun unexpectedIdOrRevisionCannotBecomeSuccessfulCiEvidence() {
        val args = json("""{"job_id":9,"run_id":7,"expected_head_sha":"$sha"}""")
        val observations = listOf(
            """{"id":10,"run_id":7,"head_sha":"$sha"}""",
            """{"id":9,"run_id":8,"head_sha":"$sha"}""",
            """{"id":9,"run_id":7,"head_sha":"${"b".repeat(40)}"}"""
        )
        for (text in observations) {
            val failure = runCatching { GitHubWorkflowEvidence.validateJob(json(text), args) }.exceptionOrNull() as GitHubReadValidation
            assertEquals("GITHUB_CI_RESOURCE_MISMATCH", failure.code)
            assertEquals(args, failure.details["requested"])
        }
    }

    @Test fun malformedResponseShapeIsAnUpstreamFailure() {
        val failure = runCatching { GitHubWorkflowEvidence.objectPayload("[]") }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_UNEXPECTED_RESPONSE", failure.code)
        assertTrue(GitHubWorkflowEvidence.objectPayload("{}").isEmpty())
    }

    @Test fun workflowSelectorsRejectInvalidNumericOrEscapingResources() {
        for (selector in listOf("0", "-1", "999999999999999999999999", "../test.yml", ".github/workflows/../test.yml", "")) {
            assertTrue(selector, runCatching { normalizeGitHubWorkflowSelector(selector) }.isFailure)
        }
        assertEquals("ci.yml", normalizeGitHubWorkflowSelector(".github/workflows/ci.yml"))
        assertEquals("123", normalizeGitHubWorkflowSelector("123"))
    }

    @Test fun latestDefinitionIsComputedByTimestampAndNeverPromotedToBuildEvidence() {
        val rows = kotlinx.serialization.json.JsonArray(
            listOf(
                json("""{"id":1,"updated_at":"2026-10-05T12:00:00Z"}"""),
                json("""{"id":2,"updated_at":"2026-10-13T12:00:00Z"}""")
            )
        )
        val evidence = GitHubWorkflowEvidence.definitionPageEvidence(rows, 54, 1, 30)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("2026-10-13T12:00:00Z"), evidence["latest_observed_updated_at"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), evidence["latest_global_established"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("PARSED_TIMESTAMPS_IN_CAPTURED_PAGE"), evidence["latest_scope"])
        for (key in listOf("branch_head_verified", "workflow_run_verified", "build_status_verified")) assertEquals(kotlinx.serialization.json.JsonPrimitive(false), evidence[key])
        val complete = GitHubWorkflowEvidence.definitionPageEvidence(rows, 2, 1, 30)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), complete["latest_global_established"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), complete["build_status_verified"])
    }

    @Test fun invalidTimestampsOrLaterPagesCannotEstablishGlobalLatest() {
        val rows = kotlinx.serialization.json.JsonArray(listOf(json("""{"id":1,"updated_at":"invalid"}"""), json("""{"id":2,"updated_at":"2026-10-13T11:00:00-01:00"}""")))
        val invalid = GitHubWorkflowEvidence.definitionPageEvidence(rows, 2, 1, 30)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("2026-10-13T12:00:00Z"), invalid["latest_observed_updated_at"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(1), invalid["unusable_timestamp_count"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), invalid["latest_global_established"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), GitHubWorkflowEvidence.definitionPageEvidence(rows, 32, 2, 30)["collection_coverage_complete"])
    }
}
