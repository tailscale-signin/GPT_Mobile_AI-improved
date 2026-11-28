package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerificationReserveTest {
    private val sha = "a".repeat(40)
    private val ci = AgentToolDefinition(
        "github_ci__selected",
        "CI",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    for (key in listOf("action", "owner", "repo", "head_sha", "expected_head_sha")) put(key, buildJsonObject { put("type", "string") })
                    for (key in listOf("run_id", "run_attempt", "per_page")) {
                        put(
                            key,
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                            }
                        )
                    }
                    put("verify_live", buildJsonObject { put("type", "boolean") })
                }
            )
            put("additionalProperties", false)
        }
    )

    private fun receipt(id: String, tool: String, arguments: JsonObject, result: JsonObject, success: Boolean = true) = buildJsonObject {
        put("receipt_id", id)
        put("call_id", id)
        put("tool", tool)
        put("arguments", arguments)
        put("result", result)
        put("binding", "accepted")
        put("repository", "owner/repo")
        put("source_head_sha", sha)
        put("trusted", true)
        put("success", success)
        put("dispatched", true)
        put("verification_eligible", true)
    }

    private fun implementation() = receipt(
        "commit",
        "github_commit__selected",
        buildJsonObject { put("action", "commit") },
        buildJsonObject {
            put("sha", sha)
            put("committed", true)
        }
    )
    private fun recorded(call: ProviderEvent.ToolCall, result: JsonObject, success: Boolean = true) = receipt(call.callId, call.name, call.arguments, result, success)
    private fun runs(attempt: Int = 1, status: String = "completed", revision: String = sha) = buildJsonObject {
        put(
            "workflow_runs",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", 7)
                        put("run_attempt", attempt)
                        put("head_sha", revision)
                        put("status", status)
                    }
                )
            )
        )
    }
    private fun jobs(attempt: Int = 1, conclusion: String = "success", revision: String = sha) = buildJsonObject {
        put("run_id", 7)
        put("run_attempt", attempt)
        put("head_sha", revision)
        put("page", 1)
        put("has_more", false)
        put("total_count", 1)
        put(
            "jobs",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", 9)
                        put("name", "Unit tests")
                        put("status", "completed")
                        put("conclusion", conclusion)
                    }
                )
            )
        )
    }

    @Test fun twoReservedCallsCollectExactCommitJobsAndCanSatisfyTheExistingAcceptanceGate() {
        val receipts = linkedMapOf("commit" to implementation())
        val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        assertEquals(JsonPrimitive("runs"), first.arguments["action"])
        assertEquals(JsonPrimitive(sha), first.arguments["head_sha"])
        assertEquals(JsonPrimitive(true), first.arguments["verify_live"])
        receipts[first.callId] = recorded(first, runs())
        val second = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0))
        assertEquals(JsonPrimitive("jobs"), second.arguments["action"])
        assertEquals(JsonPrimitive(sha), second.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(1), second.arguments["run_attempt"])
        receipts[second.callId] = recorded(second, jobs())
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 0, 0))
        val acceptance = buildJsonObject {
            put("implementation_receipts", JsonArray(listOf(JsonPrimitive("commit"))))
            put("test_receipts", JsonArray(listOf(JsonPrimitive(second.callId))))
        }
        assertEquals(GitHubAcceptanceLedger.State.PASS, GitHubRequestCompletion.engineeringAcceptance(acceptance, receipts).state)
        assertNotNull(GitHubVerificationReserve.candidate(receipts))
        assertTrue(requireNotNull(GitHubVerificationReserve.candidate(receipts)).contains("observations alone do not establish"))
    }

    @Test fun knownRunNeedsOnlyOneCallButDiscoveryCannotConsumeTheLastOrCleanupAllowance() {
        val receipts = linkedMapOf("commit" to implementation())
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0))
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 4, 4))
        val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        receipts[first.callId] = recorded(first, runs())
        assertEquals(JsonPrimitive("jobs"), GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0)?.arguments?.get("action"))
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 1))
    }

    @Test fun untrustedDifferentOwnerStaleHeadAndUnknownWritesNeverAllocateVerification() {
        val commit = implementation()
        for (changed in listOf(
            JsonObject(commit + ("trusted" to JsonPrimitive(false))),
            JsonObject(commit + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(commit + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(commit + ("tool" to JsonPrimitive("github_commit__other")))
        )) {
            assertNull(GitHubVerificationReserve.next(listOf(ci), mapOf("commit" to changed), 2, 0))
        }
        val head = JsonObject(receipt("head", "github_context__selected", buildJsonObject { put("action", "status") }, buildJsonObject {}) + mapOf("head_observation" to JsonPrimitive(true), "source_head_sha" to JsonPrimitive("b".repeat(40))))
        assertNull(GitHubVerificationReserve.next(listOf(ci), mapOf("commit" to commit, "head" to head), 2, 0))
        val uncertain = JsonObject(commit + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("DISPATCH_OUTCOME_UNKNOWN")))
        assertNull(GitHubVerificationReserve.next(listOf(ci), mapOf("commit" to commit, "unknown" to uncertain), 2, 0))
        assertNull(GitHubVerificationReserve.next(emptyList(), mapOf("commit" to commit), 2, 0))
    }

    @Test fun emptyFailedPendingAndOldAttemptObservationsDoNotCauseUnchangedPolling() {
        for ((result, success) in listOf(runs(status = "in_progress") to true, runs(revision = "b".repeat(40)) to true, buildJsonObject { put("workflow_runs", JsonArray(emptyList())) } to true, buildJsonObject {} to false)) {
            val receipts = linkedMapOf("commit" to implementation())
            val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
            receipts[first.callId] = recorded(first, result, success)
            assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        }
        val receipts = linkedMapOf("commit" to implementation())
        val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        receipts[first.callId] = recorded(first, runs())
        receipts["newer"] = receipt("newer", ci.name, first.arguments, runs(attempt = 2, status = "in_progress"))
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0))
        receipts["settled"] = receipt("settled", ci.name, first.arguments, runs(attempt = 2))
        assertEquals(JsonPrimitive(2), GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0)?.arguments?.get("run_attempt"))
        receipts["rerun"] = receipt(
            "rerun",
            ci.name,
            buildJsonObject { put("action", "rerun") },
            buildJsonObject {
                put("run_id", 7)
                put("status", "accepted")
                put("previous_run_attempt", 2)
                put("expected_run_attempt", 3)
            }
        )
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0))
    }

    @Test fun runsFromAnotherBindingRepositoryOrConnectionCannotSupplyVerificationSelectors() {
        val receipts = linkedMapOf("commit" to implementation())
        val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        val foreign = JsonObject(recorded(first, runs()) + ("call_id" to JsonPrimitive("foreign-run")))
        for ((key, value) in listOf("binding" to "another-task", "repository" to "other/repo", "tool" to "github_ci__other")) {
            val scoped = receipts + ("foreign" to JsonObject(foreign + (key to JsonPrimitive(value))))
            assertNull(GitHubVerificationReserve.next(listOf(ci), scoped, 1, 0))
            assertEquals(JsonPrimitive("runs"), GitHubVerificationReserve.next(listOf(ci), scoped, 2, 0)?.arguments?.get("action"))
            assertNull(GitHubVerificationReserve.candidate(scoped))
        }
    }

    @Test fun failedJobsRemainFailedAndTheirReadCannotBeRepeated() {
        val receipts = linkedMapOf("commit" to implementation())
        val first = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        receipts[first.callId] = recorded(first, runs())
        val second = requireNotNull(GitHubVerificationReserve.next(listOf(ci), receipts, 1, 0))
        receipts[second.callId] = recorded(second, jobs(conclusion = "failure"))
        assertNull(GitHubVerificationReserve.next(listOf(ci), receipts, 2, 0))
        val acceptance = buildJsonObject {
            put("implementation_receipts", JsonArray(listOf(JsonPrimitive("commit"))))
            put("test_receipts", JsonArray(listOf(JsonPrimitive(second.callId))))
        }
        assertEquals(GitHubAcceptanceLedger.State.FAIL, GitHubRequestCompletion.engineeringAcceptance(acceptance, receipts).state)
        assertTrue(requireNotNull(GitHubVerificationReserve.candidate(receipts)).contains("observations alone do not establish"))
    }
}
