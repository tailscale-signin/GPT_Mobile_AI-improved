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

    private fun failed(ref: String, code: String) = JsonObject(resolved(ref, success = false) + ("error_code" to JsonPrimitive(code)))

    @Test fun toolAndIssueReviewsOnABranchDoNotBecomeChangedSourceReviews() {
        for (task in listOf("Review all GitHub tools on the selected branch", "Review open issues on this branch", "Review this session and list the branch status", "Review the integration test results on branch main", "Review source tool permissions on this branch", "Review code test results on this branch", "Explain this quoted request: `Review code on this branch`")) {
            assertFalse(task, GitHubReviewComparison.matches(task))
            assertNull(task, GitHubReviewComparison.plan(task, tools, mapOf("status" to context(), "prs" to noPulls())))
        }
    }

    @Test fun directBranchAndChangedSourceReviewsStillRequireAComparison() {
        for (task in listOf(review, "Please review this branch", "Review and fix the selected branch", "Review changes on our current branch", "Review changes in current branch", "Review the latest PR branch", "Review the diff for the feature branch", "Review code on this branch", "Review source on the selected branch", "Review the source code in our current branch", "Review code/source on this branch", "[github-workflow-preset:review_branch]")) {
            assertTrue(task, GitHubReviewComparison.matches(task))
            assertTrue(task, GitHubReviewComparison.requiresComparison(task))
            assertTrue(task, requireNotNull(GitHubReviewComparison.incompleteReason(task, mapOf("status" to context(), "prs" to noPulls()))).contains("Baseline:"))
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
            val failure = GitHubReviewComparison.blockedFailure(plan)
            assertEquals("REVIEW_INPUT_REQUIRED", failure.errorCode)
            assertTrue(failure.transportComplete)
            assertEquals(JsonPrimitive("SELECT_REVIEW_BASELINE"), failure.failureDetails?.get("next_action"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
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

    @Test fun invalidBaselineRequestsCorrectedInputWithoutRepeatingTheSameLookup() {
        val ref = "release/missing"
        for (code in listOf("INVALID_ARGUMENTS", "GITHUB_VALIDATION_FAILED")) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to failed(ref, code))))
            assertEquals(JsonPrimitive("BASELINE_REQUIRED"), plan.checkpoint["state"])
            assertEquals(JsonPrimitive(true), plan.checkpoint["user_input_required"])
            assertNull(plan.nextCall)
            assertFalse(plan.checkpoint.containsKey("base_sha"))
            val failure = GitHubReviewComparison.blockedFailure(plan)
            assertEquals(code, failure.errorCode)
            assertEquals(JsonPrimitive("SELECT_REVIEW_BASELINE"), failure.failureDetails?.get("next_action"))
        }
    }

    @Test fun authenticationTransportAndRateFailuresDoNotDisproveTheRequestedBaseline() {
        val ref = "release/stable"
        for (code in listOf("AUTH_REQUIRED", "PERMISSION_DENIED", "NETWORK_TIMEOUT", "TRANSPORT_EOF", "TOOL_OUTCOME_UNKNOWN", "GITHUB_TRANSIENT_NETWORK", "GITHUB_TRANSIENT_SERVER", "GITHUB_CIRCUIT_OPEN", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            val receipts = mapOf("status" to context(), "baseline" to failed(ref, code))
            val retained = receipts.toString()
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, receipts))
            assertEquals(code, JsonPrimitive("BASE_REF_BLOCKED"), plan.checkpoint["state"])
            assertEquals(JsonPrimitive(false), plan.checkpoint["user_input_required"])
            assertNull(plan.nextCall)
            assertFalse(plan.checkpoint.containsKey("base_sha"))
            assertFalse(plan.checkpoint.toString().contains("SELECT_REVIEW_BASELINE"))
            val failure = GitHubReviewComparison.blockedFailure(plan)
            assertEquals(code, failure.errorCode)
            assertFalse(code, failure.transportComplete)
            assertEquals("BLOCKED", failure.taskOutcome)
            assertEquals(JsonPrimitive(code), failure.failureDetails?.get("error_code"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("inference_dispatched"))
            for (flag in listOf("retryable", "resume_allowed", "request_replay_allowed")) assertEquals(flag, JsonPrimitive(false), failure.failureDetails?.get(flag))
            assertFalse(requireNotNull(failure.failureDetails).containsKey("dispatched"))
            assertEquals(retained, receipts.toString())
        }
    }

    @Test fun unavailableOrInaccessibleRefRetainsItsCauseWithoutClaimingMissingInput() {
        val ref = "release/stable"
        for (code in listOf("GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REF_UNAVAILABLE")) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to failed(ref, code))))
            assertEquals(JsonPrimitive(false), plan.checkpoint["user_input_required"])
            assertNull(plan.nextCall)
            val failure = GitHubReviewComparison.blockedFailure(plan)
            assertEquals(code, failure.errorCode)
            assertTrue(failure.message.contains("baseline remains unresolved"))
            assertFalse(failure.message.contains("that exists"))
            assertFalse(failure.message.contains("Baseline:"))
        }
    }

    @Test fun blockedEventRetainsSafeRecoveryAndDispatchFactsWithoutRawBodiesOrRetryAuthority() {
        val ref = "release/stable"
        val observed = JsonObject(
            failed(ref, "GITHUB_PRIMARY_RATE_LIMITED") + json(
                """{"dispatched":true,"execution":{"phase":"READ_FAILED","http_status":429,"retry_not_before_ms":1700000000000,"requires_reconciliation":false},"result":{"message":"private response body","retry_after_ms":60000,"next_action":"REFRESH_REPOSITORY_ACCESS","recovery_actions":["SELECT_REF","REFRESH_REPOSITORY_ACCESS","private instruction"],"retryable":true,"request_replay_allowed":true,"resume_allowed":true,"authorization":"private credential"}}"""
            )
        )
        val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to observed)))
        val original = plan.checkpoint["baseline_resolution_failure"] as JsonObject
        assertEquals(JsonPrimitive(1700000000000L), original["retry_not_before_ms"])
        val failure = GitHubReviewComparison.blockedFailure(plan)
        val details = requireNotNull(failure.failureDetails)
        assertEquals(JsonPrimitive(true), details["dispatched"])
        assertEquals(JsonPrimitive("DISPATCHED"), details["dispatch_state"])
        assertEquals(JsonPrimitive("READ_FAILED"), details["resolution_dispatch_phase"])
        assertEquals(JsonPrimitive(false), details["inference_dispatched"])
        assertEquals(JsonPrimitive(429), details["http_status"])
        assertEquals(JsonPrimitive(60000), details["retry_after_ms"])
        assertEquals(JsonPrimitive(1700000000000L), details["retry_not_before_ms"])
        assertEquals(json("""{"actions":["SELECT_REF","REFRESH_REPOSITORY_ACCESS"]}""")["actions"], details["recovery_actions"])
        assertEquals(JsonPrimitive("REFRESH_REPOSITORY_ACCESS"), details["next_action"])
        for (flag in listOf("retryable", "resume_allowed", "request_replay_allowed")) assertEquals(flag, JsonPrimitive(false), details[flag])
        assertFalse(details.toString().contains("private"))
        assertFalse(details.containsKey("remote_acceptance"))
    }

    @Test fun untypedFailureDoesNotBecomeInvalidInputOrAcquireDispatchAndRetryFacts() {
        val ref = "release/stable"
        val observed = JsonObject(resolved(ref, success = false) + json("""{"error_code":"private unrecognized code","dispatched":"false","execution":{"phase":"private phase","retry_not_before_ms":-1},"result":{"message":"AUTH_REQUIRED private message","http_status":999,"retry_after_ms":"60000","next_action":"private instruction","retryable":true}}"""))
        val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to observed)))
        assertEquals(JsonPrimitive(false), plan.checkpoint["user_input_required"])
        assertNull(plan.nextCall)
        val failure = GitHubReviewComparison.blockedFailure(plan)
        assertEquals("GITHUB_OPERATION_FAILED", failure.errorCode)
        assertFalse(failure.transportComplete)
        val details = requireNotNull(failure.failureDetails)
        assertFalse(details.toString().contains("private"))
        for (key in listOf("dispatched", "dispatch_state", "http_status", "retry_after_ms", "retry_not_before_ms", "resolution_dispatch_phase")) assertFalse(key, details.containsKey(key))
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
    }

    @Test fun onlyAnExplicitExecutionReceiptCanEstablishFailedLookupTransportCompletion() {
        val ref = "release/stable"
        for ((execution, expected) in listOf("{}" to false, """{"transport_complete":"true"}""" to false, """{"transport_complete":false}""" to false, """{"transport_complete":true}""" to true)) {
            val observed = JsonObject(failed(ref, "AUTH_REQUIRED") + json("""{"execution":$execution,"result":{"transport_complete":true}}"""))
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to observed)))
            val failure = GitHubReviewComparison.blockedFailure(plan)
            assertEquals(execution, expected, failure.transportComplete)
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        }
    }

    @Test fun aFailureFromAnotherRepositoryOrExcludedReceiptCannotBlockBaselineResolution() {
        val ref = "release/stable"
        val variants = listOf(
            JsonObject(failed(ref, "AUTH_REQUIRED") + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(failed(ref, "AUTH_REQUIRED") + ("trusted" to JsonPrimitive(false))),
            JsonObject(failed(ref, "AUTH_REQUIRED") + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(failed(ref, "AUTH_REQUIRED") + ("repository_role" to JsonPrimitive("READ_SOURCE")))
        )
        for (resolution in variants) {
            val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, mapOf("status" to context(), "baseline" to resolution)))
            assertEquals(JsonPrimitive("BASE_REF_REQUIRED"), plan.checkpoint["state"])
            assertEquals(JsonPrimitive("resolve"), plan.nextCall?.arguments?.get("action"))
            assertFalse(plan.checkpoint.containsKey("baseline_resolution_failure"))
        }
    }

    @Test fun successfulRecoveryUsesPinnedComparisonWithoutDiscardingThePriorFailure() {
        val ref = "release/stable"
        val receipts = mapOf("status" to context(), "failure" to failed(ref, "NETWORK_TIMEOUT"), "baseline" to resolved(ref))
        val retained = receipts.toString()
        val plan = requireNotNull(GitHubReviewComparison.plan("$review\nBaseline: $ref", tools, receipts))
        assertEquals(JsonPrimitive("COMPARE_REQUIRED"), plan.checkpoint["state"])
        assertEquals(JsonPrimitive(base), plan.nextCall?.arguments?.get("base_sha"))
        assertEquals(JsonPrimitive(head), plan.nextCall?.arguments?.get("commit_sha"))
        assertFalse(plan.checkpoint.containsKey("baseline_resolution_failure"))
        assertEquals(retained, receipts.toString())
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
