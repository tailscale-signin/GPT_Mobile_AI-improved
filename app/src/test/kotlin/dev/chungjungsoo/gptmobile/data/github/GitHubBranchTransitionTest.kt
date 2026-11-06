package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubBranchTransitionTest {
    private val sha = "a".repeat(40)
    private fun result(owner: String = "owner", ref: String = "test", verified: Boolean = true) = buildJsonObject {
        put(
            "data",
            buildJsonObject {
                put("receipt_verified", verified)
                put("head_sha", sha)
                put(
                    "context",
                    buildJsonObject {
                        put("owner", owner)
                        put("repo", "repo")
                        put("ref", ref)
                        put("commit_sha", sha)
                    }
                )
            }
        )
    }
    private val arguments = buildJsonObject {
        put("action", "branch")
        put("branch", "test")
    }

    @Test fun verifiedBranchReceiptRetainsItsDestinationForRetryAndContinuation() {
        val destination = GitHubBranchTransition.verified("owner/repo", "github_commit__github_api", arguments, result(), true)
        assertEquals(GitHubBranchTransition.Destination("test", sha), destination)
        val receipt = buildJsonObject {
            put("tool", "github_commit__github_api")
            put("arguments", arguments)
            put("result", result())
            put("trusted", true)
            put("success", true)
        }
        assertEquals(destination, GitHubBranchTransition.fromEvidence("owner/repo", receipt))
        assertNull(GitHubBranchTransition.fromEvidence("owner/repo", JsonObject(receipt + ("verification_eligible" to JsonPrimitive(false)))))
    }

    @Test fun failuresForeignRepositoriesUnverifiedReceiptsAndWrongBranchesCannotMoveTask() {
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", arguments, result(), false))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", arguments, result(owner = "other"), true))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", arguments, result(ref = "other"), true))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", arguments, result(verified = false), true))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_read", arguments, result(), true))
    }

    @Test fun disposableFixtureCannotChangeTheTaskDestinationEvenWithAContextPayload() {
        val fixture = JsonObject(arguments + ("select_created_branch" to JsonPrimitive(false)))
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_commit", fixture, result(), true))
    }
}
