package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPatchValidationTest {
    @Test fun codeConflictMarkersBlockBeforePublicationWithoutLeakingSource() {
        val report = GitHubPatchValidation.inspect("A.kt", "fun answer() = 1", "<<<<<<< mine\nprivate content\n=======\nother\n>>>>>>> theirs")
        assertEquals("BLOCKED", report["status"]!!.jsonPrimitive.content)
        assertFalse(report.toString().contains("private content"))
        assertTrue(report["findings"]!!.jsonArray.any { it.jsonObject["line"]!!.jsonPrimitive.content == "1" })
    }

    @Test fun localSuccessDoesNotPretendCompilerLintOrTestsHaveRun() {
        val report = GitHubPatchValidation.inspect("src/A.kt", "fun answer() = 1", "fun answer() = 2  ", listOf("src/A.kt", "test/ATest.kt"))
        assertEquals("PRECHECKS_PASSED", report["status"]!!.jsonPrimitive.content)
        assertEquals("1", report["warnings"]!!.jsonPrimitive.content)
        assertTrue(report["external_checks"]!!.jsonArray.all { it.jsonObject["status"]!!.jsonPrimitive.content == "NOT_RUN" })
        assertEquals("test/ATest.kt", report["test_candidates"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(report["test_generation_context"].toString().contains("answer"))
    }

    @Test fun unchangedCredentialsAndMarkdownExamplesAreNotNewCodeFailures() {
        val existing = "val token = \"" + "ghp_" + "A".repeat(30) + "\""
        assertEquals("PRECHECKS_PASSED", GitHubPatchValidation.inspect("A.kt", existing, existing + "\n// comment")["status"]!!.jsonPrimitive.content)
        assertEquals("PRECHECKS_PASSED", GitHubPatchValidation.inspect("README.md", "", "Heading\n=======")["status"]!!.jsonPrimitive.content)
        assertEquals("BLOCKED", GitHubPatchValidation.inspect("A.kt", "", existing)["status"]!!.jsonPrimitive.content)
        assertEquals("BLOCKED", GitHubPatchValidation.inspect("A.kt", existing, "$existing\n$existing")["status"]!!.jsonPrimitive.content)
    }
}
