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
        assertFalse(text.contains("PASSED"))
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
        assertTrue(text.contains("partial verification"))
        assertTrue(text.contains("INVALID_ARGUMENTS"))
        assertTrue(text.contains("source inspection"))
        assertFalse(text.contains("@Test"))
    }

    @Test fun testingIntentDoesNotHijackImplementationRequests() {
        listOf("test all tools", "do testing of github tools", "test all functionality", "run all tests").forEach { assertTrue(it, GitHubTaskCoverage.matches(it)) }
        listOf("fix verification failure", "implement a test runner", "status", "review test coverage").forEach { assertFalse(it, GitHubTaskCoverage.matches(it)) }
    }

    @Test fun reportingRetainsCoverageEvenWhenExtractorSelectsNoToolFacts() {
        val answer = json("""{"claims":[{"kind":"not_measured","subject":"all tests"}]}""")
        val (text, report) = GitHubEvidencePolicy.render(answer, mapOf("a" to receipt("a", true)), 1000, 0, "test all tools")
        assertTrue(text.contains("github_read / ranges"))
        assertEquals(JsonPrimitive("PARTIAL"), report["task_outcome"])
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
