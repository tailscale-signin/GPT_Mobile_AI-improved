package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestCompletionTest {
    private val sha = "a".repeat(40)
    private val task = "Implement the feature"

    private fun receipt(action: String, data: JsonObject, trusted: Boolean = true) = buildJsonObject {
        put("trusted", trusted)
        put("success", true)
        put("tool", "github_ci")
        put("source_head_sha", sha)
        put("arguments", buildJsonObject { put("action", action) })
        put("result", data)
    }

    private fun jobs(attempt: Int, conclusion: String = "success", run: Int = 7) = buildJsonObject {
        put("run_id", run)
        put("run_attempt", attempt)
        put("head_sha", sha)
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

    private fun completed(extra: JsonObject? = null, selectedAttempt: Int = 1): Boolean {
        val records = linkedMapOf(
            "commit" to receipt("commit", JsonObject(emptyMap())),
            "tests" to receipt("jobs", jobs(selectedAttempt))
        )
        extra?.let { records["newer"] = it }
        val answer = buildJsonObject {
            put(
                "acceptance",
                buildJsonObject {
                    put(
                        GitHubRequestCompletion.id(task),
                        buildJsonObject {
                            put("implementation_receipts", JsonArray(listOf(JsonPrimitive("commit"))))
                            put("test_receipts", JsonArray(listOf(JsonPrimitive("tests"))))
                        }
                    )
                }
            )
        }
        val claims = listOf(buildJsonObject { put("status", "VERIFIED") })
        return GitHubRequestCompletion.evaluate(task, claims, answer, records)["completion_verified"] == JsonPrimitive(true)
    }

    @Test fun newerFailedJobAttemptInvalidatesSelectedEarlierSuccess() {
        assertFalse(completed(receipt("jobs", jobs(2, "failure"))))
    }

    @Test fun newerRunObservationInvalidatesSelectedEarlierSuccess() {
        assertFalse(
            completed(
                receipt(
                    "status",
                    buildJsonObject {
                        put("id", 7)
                        put("head_sha", sha)
                        put("run_attempt", 2)
                        put("status", "in_progress")
                    }
                )
            )
        )
    }

    @Test fun acceptedRerunInvalidatesEarlierSuccessBeforeNewJobsExist() {
        assertFalse(
            completed(
                receipt(
                    "rerun",
                    buildJsonObject {
                        put("run_id", 7)
                        put("status", "accepted")
                        put("previous_run_attempt", 1)
                        put("expected_run_attempt", 2)
                    }
                )
            )
        )
    }

    @Test fun runListObservationInvalidatesEarlierSuccess() {
        assertFalse(
            completed(
                receipt(
                    "runs",
                    buildJsonObject {
                        put(
                            "workflow_runs",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", 7)
                                        put("run_attempt", 2)
                                    }
                                )
                            )
                        )
                    }
                )
            )
        )
    }

    @Test fun unrelatedAndUntrustedAttemptsDoNotInvalidateCurrentSuccess() {
        assertTrue(completed(receipt("jobs", jobs(2, run = 8))))
        assertTrue(completed(receipt("jobs", jobs(2), trusted = false)))
        assertTrue(completed())
    }

    @Test fun latestAttemptSuccessfulJobsCanEstablishAcceptance() {
        assertTrue(
            completed(
                receipt(
                    "status",
                    buildJsonObject {
                        put("id", 7)
                        put("run_attempt", 2)
                    }
                ),
                selectedAttempt = 2
            )
        )
    }
}
