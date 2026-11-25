package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPrimaryPatchReviewTest {
    private val context = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "primary")
    private val before = "fun ready() = false"
    private val after = "fun ready() = true"
    private val receipt = buildJsonObject {
        put("binding", "task")
        put("trusted", true)
        put("verification_eligible", true)
        put("success", true)
        put("tool", "github_stage_patch__connection")
        put("repository_role", "WRITE_DESTINATION")
        put("repository", "owner/repo")
        put(
            "result",
            buildJsonObject {
                put("stage_id", "b".repeat(64))
                put("expected_head_sha", context.commitSha)
                put("branch", context.ref)
                put("committed", false)
                put("private_field", "never disclose")
                put(
                    "preview",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", "src/Ready.kt")
                                put("expected_blob_sha", "c".repeat(40))
                                put("before", before)
                                put("after", after)
                                put("preview_bounded", true)
                                put("unrelated_body", "never disclose")
                            }
                        )
                    )
                )
            }
        )
    }

    @Test fun sharesOnlyCurrentAuthenticatedPatchExcerpts() {
        val candidate = requireNotNull(GitHubPrimaryPatchReview.candidate(receipt, context, "task", context.commitSha))
        val row = candidate.excerpts.single() as JsonObject
        assertEquals(setOf("path", "before", "after"), row.keys)
        assertEquals(JsonPrimitive(after), row["after"])
        assertFalse(GitHubPrimaryPatchReview.instruction(candidate.excerpts).contains("never disclose"))
        assertNull(GitHubPrimaryPatchReview.candidate(receipt, context, "other-task", context.commitSha))
        assertNull(GitHubPrimaryPatchReview.candidate(receipt, context, "task", "e".repeat(40)))
        assertNull(GitHubPrimaryPatchReview.candidate(JsonObject(receipt + ("trusted" to JsonPrimitive(false))), context, "task", context.commitSha))
        assertNull(GitHubPrimaryPatchReview.candidate(receipt, context.copy(repo = "other"), "task", context.commitSha))
    }

    @Test fun reviewerCanOnlyReturnBoundedAdvisoryFindings() {
        assertEquals("""["Check the null branch"]""", GitHubPrimaryPatchReview.assessment("""{"findings":["Check the null branch"]}"""))
        assertEquals("[]", GitHubPrimaryPatchReview.assessment("""{"findings":[]}"""))
        assertNull(GitHubPrimaryPatchReview.assessment("""{"findings":[],"approve":true}"""))
        assertNull(GitHubPrimaryPatchReview.assessment("""{"findings":[false]}"""))
    }
}
