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
}
