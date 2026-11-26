package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubCiDiagnosisTest {
    private val head = "a".repeat(40)
    private val tested = "b".repeat(40)
    private val digest = "f".repeat(64)
    private val paths = setOf("first/Foo.kt", "second/Foo.kt", "Foo.kt")
    private val identity = buildJsonObject {
        put("head_sha", head)
        put("repository", "owner/repo")
        put("run_id", 8)
        put("job_id", 7)
        put("run_attempt", 2)
    }
    private fun log(text: String) = GitHubCiDiagnosis.group(listOf(buildJsonObject { put("text", "ERROR: $text") }), paths, identity).single().jsonObject

    @Test fun exactChangedPathIsSeparatedFromAnAmbiguousBasenameAndAnotherDirectory() {
        assertEquals(JsonPrimitive("AMBIGUOUS_BASENAME"), log("Foo.kt:7")["location_status"])
        assertEquals(JsonArray(emptyList()), log("unexpected/first/Foo.kt:7")["matching_changed_paths"])
        assertEquals(JsonArray(listOf(JsonPrimitive("first/Foo.kt"))), log("file:///home/runner/work/repo/repo/first/Foo.kt:7")["matching_changed_paths"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Foo.kt"))), log("./Foo.kt:7")["matching_changed_paths"])
        assertEquals(identity["run_attempt"], log("failure")["run_attempt"])
    }

    @Test fun annotationUsesTheCheckShaAndDoesNotTurnAnotherFilesBasenameIntoEvidence() {
        val row = Json.parseToJsonElement("""[{"annotation_level":"failure","message":"bad","path":"second/Foo.kt","start_line":8,"end_line":9}]""") as JsonArray
        val record = GitHubCiDiagnosis.annotations(row, paths, identity, 19, head) { it }.single()
        assertEquals(JsonArray(listOf(JsonPrimitive("second/Foo.kt"))), record["matching_changed_paths"])
        assertEquals(JsonPrimitive(8), record["start_line"])
        assertEquals(JsonPrimitive(head), record["check_head_sha"])
    }

    @Test fun evidenceLinksUseTheReportedRevisionAndOmitUnknownSourceLocations() {
        val base = "https://enterprise.example/owner/repo"
        val receipt = Json.parseToJsonElement("""{"tool":"github_ci","arguments":{"action":"failure"},"result":{"diagnosis":[{"source":"JUNIT_ARTIFACT","test_name":"verify","source_path":"second/Foo.kt","head_sha":"$head","tested_sha":"$tested","job_id":7,"run_id":8},{"source":"JOB_LOG","source_path":"Foo.kt","head_sha":"$head"}]}}""").jsonObject
        assertEquals("$base/blob/$tested/second/Foo.kt", GitHubEvidenceLink.url(base, "/diagnosis/0/test_name", "verify", receipt))
        assertEquals("$base/actions/runs/8/job/7", GitHubEvidenceLink.url(base, "/diagnosis/0/job_id", "7", receipt))
        assertEquals(null, GitHubEvidenceLink.url(base, "/diagnosis/1/source_path", "Foo.kt", receipt))
    }

    @Test fun junitRequiresTheArtifactFromThisAttemptAndKeepsTheSyntheticCheckoutSeparate() = runBlocking {
        var calls = 0
        val artifact = """{"id":19,"name":"ci-junit-8-2-validate","size_in_bytes":128,"expired":false,"digest":"sha256:$digest","workflow_run":{"id":8,"head_sha":"$head"}}"""
        val receipt = """{"schema_version":1,"run_id":"8","run_attempt":"2","job":"validate","repository":"owner/repo","head_sha":"$head","tested_sha":"$tested","tracked_files_clean":true,"failures":[{"class_name":"my.Foo","name":"checks","outcome":"FAILURE","source_path":"second/Foo.kt","report_sha256":"$digest"}]}"""
        suspend fun read(attempt: Int, value: String = receipt) = GitHubCiTestEvidence.read(
            listOf("Artifact ci-junit-8-$attempt-validate has been successfully uploaded! Final size is 128 bytes. Artifact ID is 19"),
            identity,
            paths,
            10000,
            metadata = {
                calls++
                Json.parseToJsonElement(artifact).jsonObject
            },
            archive = { _, _ -> value },
            redact = { it }
        )
        assertEquals("ATTEMPT_MISMATCH", read(1).first)
        assertEquals(0, calls)
        assertEquals("JUNIT_IDENTITY_MISMATCH", read(2, receipt.replace("\"run_attempt\":\"2\"", "\"run_attempt\":\"1\"")).first)
        assertEquals("JUNIT_CHECKOUT_UNVERIFIED", read(2, receipt.replace("\"tracked_files_clean\":true", "\"tracked_files_clean\":false")).first)
        val result = read(2).second.single()
        assertEquals(JsonPrimitive(tested), result["tested_sha"])
        assertEquals(JsonPrimitive("checks"), result["test_name"])
        assertEquals(JsonArray(emptyList()), result["matching_changed_paths"])
        assertFalse(result.containsKey("start_line"))
    }
}
