package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewBaselineTest {
    private val head = "a".repeat(40)
    private val base = "b".repeat(40)
    private val review = "Review the selected branch against its base."
    private val tools = listOf(
        AgentToolDefinition("github_context", "Read repository context", json("""{"type":"object","properties":{"action":{"type":"string"},"ref":{"type":"string"},"base_sha":{"type":"string"},"commit_sha":{"type":"string"},"include_patches":{"type":"boolean"}},"required":["action"],"additionalProperties":false}""")),
        AgentToolDefinition("github_pr", "Read pull requests", json("""{"type":"object","properties":{"action":{"type":"string"},"state":{"type":"string"},"per_page":{"type":"integer"}},"required":["action"],"additionalProperties":false}"""))
    )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun receipt(tool: String, arguments: JsonObject, data: JsonObject, repository: String = "owner/repo", success: Boolean = true) = buildJsonObject {
        put("repository", repository)
        put("trusted", true)
        put("verification_eligible", true)
        put("success", success)
        put("tool", tool)
        put("arguments", arguments)
        put("result", data)
    }

    private fun context() = receipt("github_context", json("""{"action":"status"}"""), json("""{"branch":"main","commit_sha":"$head","repository":{"default_branch":"main"}}"""))
    private fun noPulls() = receipt("github_pr", json("""{"action":"list","state":"open","per_page":100}"""), json("""{"items":[],"has_more":false}"""))
    private fun resolved(ref: String, repository: String = "owner/repo", success: Boolean = true) = receipt(
        "github_context",
        buildJsonObject {
            put("action", "resolve")
            put("ref", ref)
        },
        buildJsonObject { if (success) put("commit_sha", base) },
        repository,
        success
    )

    @Test fun toolAndIssueReviewsOnABranchDoNotBecomeChangedSourceReviews() {
        for (task in listOf("Review all GitHub tools on the selected branch", "Review open issues on this branch", "Review this session and list the branch status", "Review the integration test results on branch main")) {
            assertFalse(task, GitHubReviewComparison.matches(task))
            assertNull(task, GitHubReviewComparison.plan(task, tools, mapOf("status" to context(), "prs" to noPulls())))
        }
    }

    @Test fun directBranchAndChangedSourceReviewsStillRequireAComparison() {
        for (task in listOf(review, "Please review this branch", "Review and fix the selected branch", "Review changes on our current branch", "Review changes in current branch", "Review the latest PR branch", "Review the diff for the feature branch", "[github-workflow-preset:review_branch]")) {
            assertTrue(task, GitHubReviewComparison.matches(task))
        }
    }

    @Test fun defaultBranchWithoutBaselineStaysBlockedWithoutInventingAComparison() {
        for (task in listOf(review, "Review the selected branch against its base branch", "Review this branch against its baseline\nAcceptance criteria: inspect the source")) {
            val plan = requireNotNull(GitHubReviewComparison.plan(task, tools, mapOf("status" to context(), "prs" to noPulls())))
            assertEquals(task, JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
            assertEquals(JsonPrimitive(true), plan.checkpoint["user_input_required"])
            assertFalse(plan.checkpoint.containsKey("base_sha"))
            assertNull(plan.nextCall)
            assertTrue(plan.checkpoint.toString().contains("Baseline:"))
        }
    }

    @Test fun explicitTagBranchAndShortCommitResolveBeforeComparingPinnedRevisions() {
        for (ref in listOf("v0.9.66.7", "release/stable", "97f10ff7a1f3")) {
            val task = "$review\nBaseline: $ref"
            val plan = requireNotNull(GitHubReviewComparison.plan(task, tools, mapOf("status" to context())))
            assertEquals(JsonPrimitive("BASE_REF_REQUIRED"), plan.checkpoint["state"])
            assertEquals("github_context", plan.nextCall?.name)
            assertEquals(
                buildJsonObject {
                    put("action", "resolve")
                    put("ref", ref)
                },
                plan.nextCall?.arguments
            )
            assertFalse(plan.checkpoint.containsKey("base_sha"))
            val pinned = requireNotNull(GitHubReviewComparison.plan(task, tools, mapOf("status" to context(), "baseline" to resolved(ref))))
            assertEquals(JsonPrimitive("EXPLICIT_BASE_REF"), pinned.checkpoint["comparison_policy"])
            assertEquals(JsonPrimitive(base), pinned.checkpoint["base_sha"])
            assertEquals(JsonPrimitive(head), pinned.nextCall?.arguments?.get("commit_sha"))
            assertEquals(JsonPrimitive(base), pinned.nextCall?.arguments?.get("base_sha"))
        }
    }

    @Test fun quotedLiteralRefsAreAcceptedOnlyAfterTheUserBaselineSelector() {
        for (literal in listOf("`release/stable`", "\"release/stable\"", "'release/stable'", "“release/stable”")) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $literal", tools, mapOf("status" to context())))
            assertEquals(JsonPrimitive("release/stable"), plan.nextCall?.arguments?.get("ref"))
        }
        for (excerpt in listOf("\"Baseline: release/stable\"", "`Baseline: release/stable`", "```text\nBaseline: release/stable\n```", "> Baseline: `release/stable`")) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nHistorical excerpt:\n$excerpt", tools, mapOf("status" to context(), "prs" to noPulls())))
            assertEquals(excerpt, JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
            assertNull(plan.nextCall)
        }
    }

    @Test fun explicitFullShaIsNormalizedWithoutAnUnnecessaryResolution() {
        val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: `${base.uppercase()}`", tools, mapOf("status" to context())))
        assertEquals(JsonPrimitive("EXPLICIT_BASE_SHA"), plan.checkpoint["comparison_policy"])
        assertEquals(JsonPrimitive(base), plan.checkpoint["base_sha"])
        assertEquals(JsonPrimitive("changes"), plan.nextCall?.arguments?.get("action"))
    }

    @Test fun explicitRefCannotBorrowAnotherRepositoryOrUntrustedResolution() {
        val ref = "release/stable"
        val variants = listOf(
            resolved(ref, "other/repo"),
            JsonObject(resolved(ref) + ("trusted" to JsonPrimitive(false))),
            JsonObject(resolved(ref) + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(resolved(ref) + ("repository_role" to JsonPrimitive("READ_SOURCE")))
        )
        for (resolution in variants) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to resolution)))
            assertEquals(JsonPrimitive("BASE_REF_REQUIRED"), plan.checkpoint["state"])
            assertEquals(JsonPrimitive("resolve"), plan.nextCall?.arguments?.get("action"))
            assertFalse(plan.checkpoint.containsKey("base_sha"))
        }
    }

    @Test fun unavailableBaselineRequestsCorrectedInputWithoutRepeatingTheSameLookup() {
        val ref = "release/missing"
        val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to resolved(ref, success = false))))
        assertEquals(JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
        assertEquals(JsonPrimitive(true), plan.checkpoint["user_input_required"])
        assertNull(plan.nextCall)
        assertFalse(plan.checkpoint.containsKey("base_sha"))
    }

    @Test fun matchingCommitNamesFromAnotherRepositoryCannotVerifyTheReview() {
        val task = "$review\nBaseline: $base"
        fun changes(repository: String) = receipt(
            "github_context",
            json("""{"action":"changes","base_sha":"$base","commit_sha":"$head","include_patches":true}"""),
            json("""{"base_sha":"$base","head_sha":"$head","files":[{"filename":"src/File.kt"}],"files_total":1,"has_more":false}"""),
            repository
        )
        fun source(repository: String) = receipt(
            "github_read",
            json("""{"commit_sha":"$head","ranges":[{"path":"src/File.kt"}]}"""),
            json("""{"commit_sha":"$head"}"""),
            repository
        )
        val foreignComparison = requireNotNull(GitHubReviewComparison.plan(task, tools, mapOf("status" to context(), "changes" to changes("other/repo"))))
        assertEquals(JsonPrimitive("COMPARE_REQUIRED"), foreignComparison.checkpoint["state"])
        assertEquals(JsonPrimitive("changes"), foreignComparison.nextCall?.arguments?.get("action"))
        val receipts = mapOf("status" to context(), "changes" to changes("owner/repo"), "source" to source("other/repo"))
        assertTrue(requireNotNull(GitHubReviewComparison.incompleteReason(task, receipts)).contains("no relevant changed-source read"))
        assertNull(GitHubReviewComparison.incompleteReason(task, receipts + ("source" to source("owner/repo"))))
    }
}
