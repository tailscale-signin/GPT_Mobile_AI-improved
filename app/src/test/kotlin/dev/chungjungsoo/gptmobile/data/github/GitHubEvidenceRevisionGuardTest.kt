package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubEvidenceRevisionGuardTest {
    private val actual = "a".repeat(40)
    private val requested = "c".repeat(40)
    private val retained = buildJsonObject {
        put("commit_sha", actual)
        put("ranges", JsonArray(listOf(buildJsonObject { put("path", "historical/Old.kt") })))
    }
    private fun args() = buildJsonObject {
        put("action", "read_ranges")
        put("commit_sha", requested)
        put("evidence_id", "retained-source")
        put("ref", "feature")
        put(
            "ranges",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "src/Current.kt")
                        put("start_line", 8)
                        put("max_lines", 20)
                    }
                )
            )
        )
    }

    @Test fun mismatchPreservesBothIdentitiesAndRequiresAnExplicitRevisionChoice() {
        val original = args()
        val failure = runCatching { GitHubEvidenceRevisionGuard.validate("owner", "repo", original, retained) }.exceptionOrNull() as GitHubReadValidation
        assertEquals("EVIDENCE_REVISION_MISMATCH", failure.code)
        assertEquals(JsonPrimitive(requested), failure.details["requested_commit_sha"])
        assertEquals(JsonPrimitive(actual), failure.details["evidence_commit_sha"])
        assertEquals(JsonPrimitive(false), failure.details["read_dispatched"])
        assertEquals(JsonPrimitive(false), failure.details["automatic_repair_allowed"])
        assertFalse(failure.details.containsKey("correction_call"))
        val choices = failure.details.getValue("corrective_actions").jsonArray.map { it.jsonObject.getValue("arguments").jsonObject }
        assertEquals(JsonPrimitive(actual), choices[0]["commit_sha"])
        assertEquals(JsonPrimitive("retained-source"), choices[0]["evidence_id"])
        assertFalse("ref" in choices[0])
        assertEquals(original["ranges"], choices[1]["ranges"])
        assertEquals(original["commit_sha"], choices[1]["commit_sha"])
        assertFalse("ref" in choices[1])
        assertFalse("evidence_id" in choices[1])
        assertEquals(original, args())
        assertNull(GitHubSourceReadRecovery.correctedArguments("github_read", original, failure.details))
    }

    @Test fun missingCallerRangesNeverTurnHistoricalPathsIntoCurrentSourceEvidence() {
        val failure = runCatching { GitHubEvidenceRevisionGuard.validate("owner", "repo", JsonObject(args() - "ranges"), retained) }.exceptionOrNull() as GitHubReadValidation
        assertEquals(1, failure.details.getValue("corrective_actions").jsonArray.size)
        assertFalse(failure.details.toString().contains("historical/Old.kt"))
    }

    @Test fun malformedRangesNeverBecomeAnExecutableNewRevisionProposal() {
        val invalid = JsonObject(args() + ("ranges" to JsonArray(listOf(buildJsonObject { put("path", "../outside.kt") }))))
        val failure = runCatching { GitHubEvidenceRevisionGuard.validate("owner", "repo", invalid, retained) }.exceptionOrNull() as GitHubReadValidation
        assertEquals(1, failure.details.getValue("corrective_actions").jsonArray.size)
    }

    @Test fun matchingOrOmittedRevisionReopensTheOriginalSource() {
        GitHubEvidenceRevisionGuard.validate("owner", "repo", JsonObject(args() - "commit_sha"), retained)
        GitHubEvidenceRevisionGuard.validate("owner", "repo", JsonObject(args() + ("commit_sha" to JsonPrimitive(actual.uppercase()))), retained)
    }
}
