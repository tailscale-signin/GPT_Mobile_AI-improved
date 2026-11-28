package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskCoverageTest {
    @org.junit.Test fun interruptionAnalysisAndItsRecoveryPromptRemainBounded() {
        org.junit.Assert.assertTrue(GitHubTaskCoverage.boundedDiagnostic("Try to analyze why these interruptions are happening."))
        org.junit.Assert.assertTrue(GitHubTaskCoverage.boundedDiagnostic("Original task: analyze why these interruptions are happening. Recovery action: RETRY_FAILED_STAGE"))
        org.junit.Assert.assertFalse(GitHubTaskCoverage.boundedDiagnostic("Implement cancellation tracing across all workers"))
    }
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun receipt(id: String, success: Boolean, trusted: Boolean = true) = json("""{"receipt_id":"$id","tool":"github_read__api","trusted":$trusted,"success":$success,"arguments":{"action":"ranges"},"error_code":${if (success) "null" else "\"INVALID_ARGUMENTS\""},"result":{"path":"Test.kt","content":"@Test fun passes() {}"}}""")

    @Test fun continuationCheckpointPreservesCoverageWithoutGrantingCompletion() {
        val verdict = json("""{"schema":"github.verification.v1","task":"test all tools","task_outcome":"PARTIAL","operation_coverage":[{"tool":"github_read","receipt_ids":["r"]}]}""")
        val checkpoint = requireNotNull(GitHubEngineeringMemoryPolicy.checkpoint(verdict))
        assertEquals(verdict["operation_coverage"], checkpoint["operation_coverage"])
        assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
        assertEquals(JsonPrimitive("test all tools"), checkpoint["task"])
        assertEquals(null, GitHubEngineeringMemoryPolicy.checkpoint(json("""{"schema":"untrusted"}""")))
    }

    @Test fun checkpointKeepsVerifiedIssueFindingAndTheActualRecoveryDecision() {
        val verdict = json("""{"schema":"github.verification.v1","task":"Fix all issues","task_outcome":"PARTIAL","claims":[{"kind":"fact","status":"VERIFIED","receipt_id":"issue-42","pointer":"/title","observed":"Verifier drops the final answer"},{"kind":"fact","status":"NOT_MEASURED","receipt_id":"unknown","pointer":"/state","observed":"closed"}],"remaining_requirements":[{"id":"repair-42","requirement":"Fix issue 42 and verify its final state","status":"NOT_TESTED"}],"recovery_action":"INSPECT_EXECUTION_FAILURE","retryable_without_new_input":false}""")
        val checkpoint = requireNotNull(GitHubEngineeringMemoryPolicy.checkpoint(verdict))
        val finding = (checkpoint["retained_findings"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("issue-42"), finding["receipt_id"])
        assertEquals(JsonPrimitive("Verifier drops the final answer"), finding["observed"])
        assertEquals(JsonPrimitive("repair-42"), (checkpoint["next_requirement"] as JsonObject)["id"])
        assertEquals(JsonPrimitive("INSPECT_EXECUTION_FAILURE"), checkpoint["recovery_action"])
        assertEquals(JsonPrimitive(false), checkpoint["retryable_without_new_input"])
        assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
    }

    @Test fun workingBriefPrioritizesIssueFindingsAndExcludesUntrustedReceiptsWithinItsBound() {
        val issue = json("""{"trusted":true,"success":true,"repository":"owner/repo","semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_issue","source_result_sha256":"issue-source","resource_observations":[{"number":42,"title":"Verifier drops the final answer","state":"open"}]}}""")
        val receipts = linkedMapOf("issue" to issue, "untrusted" to JsonObject(issue + ("trusted" to JsonPrimitive(false))))
        repeat(30) { index ->
            receipts["metadata-$index"] = json("""{"trusted":true,"success":true,"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_context","source_result_sha256":"source-$index","commit_sha":"${"a".repeat(40)}"}}""")
        }
        val brief = GitHubEngineeringMemoryPolicy.workingBrief("Fix all issues", null, receipts)
        val findings = json(brief)["findings"] as JsonArray
        assertEquals(JsonPrimitive("issue"), (findings.first() as JsonObject)["receipt_id"])
        assertFalse(findings.any { (it as JsonObject)["receipt_id"] == JsonPrimitive("untrusted") })
        assertTrue(brief.contains("Verifier drops the final answer"))
        assertTrue(brief.length <= 6000)
        assertTrue(brief.contains("not current proof or authorization"))
    }

    @Test fun workingBriefSelectsAnOlderNamedIssueBeforeTheCandidateLimit() {
        val receipts = (1..60).associate { number ->
            "receipt-$number" to json("""{"trusted":true,"success":true,"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_issue","source_result_sha256":"issue-$number","resource_observations":[{"number":$number,"title":"Issue $number"}]}}""")
        }
        val brief = json(GitHubEngineeringMemoryPolicy.workingBrief("Investigate issue #2", null, receipts))
        val first = (brief["findings"] as JsonArray).first() as JsonObject
        assertEquals(JsonPrimitive("receipt-2"), first["receipt_id"])
    }

    @Test fun securityFieldsRemainVerifiableWhileAppMetadataDoesNotCrowdOutChecks() {
        val source = json("""{"permissions":{"push":false},"verification":{"verified":false},"app":{"node_id":"noise"},"checks":19}""")
        val facts = GitHubEvidencePolicy.facts(source)
        assertEquals(JsonPrimitive(false), facts["/permissions/push"])
        assertEquals(JsonPrimitive(false), facts["/verification/verified"])
        assertEquals(JsonPrimitive(19), facts["/checks"])
        assertFalse(facts.keys.any { it.startsWith("/app/") })
    }

    @Test fun historicalCoverageDoesNotMakeStaleCheckStateCurrent() {
        val source = json("""{"receipt_id":"r","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"checks"},"observed_at_ms":1000,"epoch":0,"result":{"state":"PASSED","checks":19}}""")
        val (text, report) = GitHubEvidencePolicy.testing(mapOf("r" to source), 200000, 0, "test all tools")
        // Historical endpoint coverage may pass; a stale current-state claim may not.
        assertFalse(text.contains("| Commit check summary | PASSED |"))
        assertFalse((report["claims"] as JsonArray).filterIsInstance<JsonObject>().any { it["pointer"] == JsonPrimitive("/state") && it["status"] == JsonPrimitive("VERIFIED") })
        assertTrue(text.contains("github_context / checks"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
    }

    @Test fun successfulSourceReadsNeverBecomePassingTestsAndFailuresArePreserved() {
        val receipts = mapOf("a" to receipt("a", true), "b" to receipt("b", false), "c" to receipt("c", true, false))
        val row = GitHubTaskCoverage.rows(receipts).single()
        assertEquals(JsonPrimitive(1), row["successful_observations"])
        assertEquals(JsonPrimitive(1), row["failed_observations"])
        assertEquals(2, (row["receipt_ids"] as JsonArray).size)
        val text = GitHubTaskCoverage.render(receipts)
        assertTrue(text.contains("Task outcome: blocked; partial coverage retained"))
        assertTrue(text.contains("INVALID_ARGUMENTS"))
        assertTrue(text.contains("source inspection"))
        assertFalse(text.contains("@Test"))
    }

    @Test fun testingIntentDoesNotHijackImplementationRequests() {
        listOf("Do a massive full stress test benchmark and list all the issues encountered.", "Perform a comprehensive benchmark", "test all tools", "do testing of github tools", "test all functionality", "run all tests", "Do a full test on the GitHub integration tool system, benchmark, give it a score", "Please perform a comprehensive integration test").forEach { assertTrue(it, GitHubTaskCoverage.matches(it)) }
        listOf("fix verification failure", "implement a test runner", "status", "review test coverage").forEach { assertFalse(it, GitHubTaskCoverage.matches(it)) }
    }

    @Test fun diagnosticsAreBoundedButExplicitHistoryAuditsRemainAvailable() {
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("test"))
        assertTrue(GitHubTaskCoverage.boundedDiagnostic("Do a massive full stress test benchmark and list all the issues encountered."))
        assertTrue(GitHubTaskCoverage.boundedDiagnostic("Can you explain in depth the issues in this very session with the GitHub integration?"))
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("Test pagination across all workflow history"))
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("Implement a workflow history viewer"))
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("Run all tests"))
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("Run the full unit test suite and fix every failure"))
    }

    @Test fun reportingRetainsCoverageEvenWhenExtractorSelectsNoToolFacts() {
        val answer = json("""{"claims":[{"kind":"not_measured","subject":"all tests"}]}""")
        val (text, report) = GitHubEvidencePolicy.render(answer, mapOf("a" to receipt("a", true)), 1000, 0, "test all tools")
        assertTrue(text.contains("github_read / ranges"))
        assertEquals(JsonPrimitive("BLOCKED"), report["task_outcome"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertFalse(text.contains("Would you like"))
    }

    @Test fun commitMessagesUseTheirOwnRevisionInsteadOfSnapshotHead() {
        val head = "a".repeat(40)
        val older = "b".repeat(40)
        val source = json("""{"trusted":true,"success":true,"source_head_sha":"$head","result":{"items":[{"sha":"$older","commit":{"message":"Older change"}}]}}""")
        assertEquals(JsonPrimitive(older), GitHubEvidenceExcerpts.collect(source).values.single()["revision"])
    }

    @Test fun compactProjectionPreservesOperationalResultsAndFullOption() {
        val original = json("""{"state":"PASSED","checks":1,"check_details":{"check_runs":[{"name":"unit tests","status":"completed","conclusion":"success","head_sha":"abc","app":{"permissions":{"contents":"write"}},"node_id":"noise"}]},"has_more":false}""")
        val compact = GitHubResultProjection.project("github_context", json("""{"action":"checks"}"""), original)
        assertTrue(compact.toString().contains("unit tests"))
        assertTrue(compact.toString().contains("conclusion"))
        assertFalse(compact.toString().contains("permissions"))
        assertEquals(original, GitHubResultProjection.project("github_context", json("""{"action":"checks","response_detail":"full"}"""), original))
        assertEquals(original, GitHubResultProjection.project("github_write", json("""{"action":"checks"}"""), original))
    }
}
