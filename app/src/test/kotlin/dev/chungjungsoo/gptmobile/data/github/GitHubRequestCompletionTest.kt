package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestCompletionTest {
    private val sha = "a".repeat(40)
    private val task = "Implement the feature"

    @Test fun compoundChangeRequestsCannotCompleteFromAnUnrelatedReadFact() {
        for (request in listOf("Review the repository and implement feature X", "Can you inspect this repository and then fix the bug?", "Read README.md; update the repository docs", "What changed? Please fix the bug", "Explain the architecture and implement the feature")) {
            assertFalse(request, GitHubRequestCompletion.informational(request))
            assertTrue(request, GitHubRequestCompletion.requiresRepositoryEvidence(request))
            val claims = listOf(buildJsonObject { put("status", "VERIFIED") })
            val result = GitHubRequestCompletion.evaluate(request, claims, JsonObject(emptyMap()), mapOf("read" to receipt("status", JsonObject(emptyMap()))))
            assertFalse(request, result["completion_verified"] == JsonPrimitive(true))
        }
    }

    @Test fun knowledgeQuestionsAndQuotedCommandsDoNotRequestRepositoryWork() {
        for (request in listOf("Explain how to create a branch and push commits", "Review this log: \"then deploy the branch\"", "Review this comment: 'fix the repository and run tests'", "Review this example:\n```text\nimplement the feature\n```", "Review this excerpt:\n> Update the repository and run tests")) {
            assertTrue(request, GitHubRequestCompletion.informational(request))
            assertFalse(request, GitHubRequestCompletion.requiresRepositoryEvidence(request))
        }
    }

    @Test fun sessionReviewRequiresEvidenceLinkedFindingsAndLabeledRecommendations() {
        val task = "Review this saved GitHub session and give recommendations."
        assertTrue(GitHubRequestCompletion.informational(task))
        val requirements = GitHubRequestCompletion.requirements(task)
        val taskId = GitHubRequestCompletion.id(requirements.first())
        val findingId = GitHubRequestCompletion.id(requirements.single { it.startsWith("Evidence-backed findings") })
        val recommendationId = GitHubRequestCompletion.id(requirements.single { it.startsWith("Recommendations are clearly labeled") })
        val claims = listOf(
            buildJsonObject {
                put("kind", "fact")
                put("status", "VERIFIED")
                put("receipt_id", "live-read")
            },
            buildJsonObject {
                put("kind", "recommendation")
                put("status", "INFERRED")
                put("sources", JsonArray(listOf(buildJsonObject { put("receipt_id", "live-read") })))
            }
        )
        val answer = buildJsonObject {
            put(
                "requirement_claims",
                buildJsonObject {
                    put(taskId, JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1))))
                    put(findingId, JsonArray(listOf(JsonPrimitive(0))))
                    put(recommendationId, JsonArray(listOf(JsonPrimitive(1))))
                }
            )
        }
        val result = GitHubRequestCompletion.evaluate(task, claims, answer, mapOf("live-read" to receipt("status", JsonObject(emptyMap()))))
        assertTrue(result["completion_verified"] == JsonPrimitive(true))
        val rows = (result["requirements"] as JsonArray).filterIsInstance<JsonObject>()
        assertEquals(JsonArray(listOf(JsonPrimitive("live-read"))), rows.single { it["id"] == JsonPrimitive(findingId) }["receipt_ids"])
        val unlinked = GitHubRequestCompletion.evaluate(task, claims, answer, emptyMap())
        assertFalse(unlinked["completion_verified"] == JsonPrimitive(true))
        assertTrue(unlinked.toString().contains("missing_evidence_ids"))
    }

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
