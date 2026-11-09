package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
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

class GitHubRefAvailabilityTest {
    @Test fun repositoryPrecheckRetainsItsExactTargetAndRecoveryWithoutClearingOtherNegatives() {
        val gate = GitHubRefAvailability(now = { 1000 }, cooldownMillis = 5000)
        gate.failed("/repos/Owner/Repo", 404, "Not found")
        gate.failed("/repos/owner/other", 404, "Not found")
        gate.failed("/repos/owner/repo/branches/missing", 404, "Not found")
        val failure = runCatching { gate.requireAvailable("/repos/owner/repo/commits/main") }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", failure.code)
        assertTrue(failure.message.orEmpty().startsWith("GITHUB_REPOSITORY_UNAVAILABLE:"))
        assertEquals(JsonPrimitive("repository_admission"), failure.details["failed_stage"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), failure.details["dispatch_state"])
        assertEquals(JsonPrimitive("NO"), failure.details["remote_acceptance"])
        assertEquals(JsonPrimitive(5000), failure.details["retry_after_ms"])
        gate.revalidateRepository("/repos/owner/repo")
        gate.requireAvailable("/repos/owner/repo/commits/main")
        assertTrue(runCatching { gate.requireAvailable("/repos/owner/other") }.isFailure)
        assertTrue(runCatching { gate.requireAvailable("/repos/owner/repo/branches/missing") }.isFailure)
    }

    @Test fun explicitRepositoryRefreshDoesOneFreshLookupAndLeavesAnotherTargetBlocked() = runTest {
        var requests = 0
        var available = false
        val gate = GitHubRefAvailability()
        gate.failed("/repos/owner/other", 404, "Not found")
        val http = HttpClient(
            MockEngine {
                requests++
                if (available) respond("""{"id":1,"full_name":"owner/repo","default_branch":"main"}""") else respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http, refAvailability = gate)
            val args = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
            }
            assertTrue(runCatching { client.execute("get_repository", args) }.isFailure)
            assertTrue(runCatching { client.execute("get_repository", args) }.exceptionOrNull() is GitHubReadValidation)
            assertEquals(1, requests)
            available = true
            client.execute("get_repository", JsonObject(args + ("refresh" to JsonPrimitive(true))))
            assertEquals(2, requests)
            assertTrue(runCatching { gate.requireAvailable("/repos/owner/other") }.isFailure)
        } finally {
            http.close()
        }
    }

    @Test fun unavailableRefBlocksDependentReadsWithoutChangingRepositoryOrRefKind() {
        val gate = GitHubRefAvailability()
        gate.failed("/repos/Owner/Repo/commits/missing%2Fbranch", 422, "No commit found for SHA")
        val failure = runCatching { gate.requireAvailable("/repos/owner/repo/commits/missing%2Fbranch/check-runs") }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_REF_UNAVAILABLE", failure.code)
        assertEquals(JsonPrimitive(false), failure.details["dispatched"])
        assertEquals(JsonPrimitive(false), failure.details["automatic_branch_substitution"])
        assertEquals(JsonPrimitive("missing/branch"), failure.details["ref"])
        gate.requireAvailable("/repos/owner/other/commits/missing%2Fbranch")
        gate.requireAvailable("/repos/owner/repo/branches/missing%2Fbranch")
        gate.requireAvailable("/repos/owner/repo/commits/main")
    }

    @Test fun permissionsServerErrorsAndMissingProtectionDoNotPoisonRefResolution() {
        val gate = GitHubRefAvailability()
        for (status in listOf(401, 403, 429, 500, 503)) gate.failed("/repos/o/r/commits/main", status, "unavailable")
        gate.failed("/repos/o/r/commits/main", 422, "An unrelated field is invalid")
        gate.failed("/repos/o/r/branches/main/protection", 404, "Not found")
        gate.requireAvailable("/repos/o/r/commits/main")
        gate.requireAvailable("/repos/o/r/branches/main")
    }

    @Test fun explicitRevalidationExpiryAndCredentialReplacementClearOnlyTheirScope() {
        var time = 0L
        val gate = GitHubRefAvailability(now = { time }, cooldownMillis = 100)
        gate.failed("/repos/o/r/branches/feature", 404, "Not found")
        gate.failed("/repos/o/other/branches/feature", 404, "Not found")
        gate.revalidate("/repos/o/r", "feature")
        gate.requireAvailable("/repos/o/r/branches/feature")
        assertTrue(runCatching { gate.requireAvailable("/repos/o/other/branches/feature") }.isFailure)
        time = 100
        gate.requireAvailable("/repos/o/other/branches/feature")
        gate.failed("/repos/o/r/branches/feature", 404, "Not found")
        gate.clear()
        gate.requireAvailable("/repos/o/r/branches/feature")
    }

    @Test fun negativeObservationsAreBoundedAndBranchAndTagNamespacesStayDistinct() {
        val gate = GitHubRefAvailability()
        repeat(129) { gate.failed("/repos/o/r/branches/ref$it", 404, "Not found") }
        gate.requireAvailable("/repos/o/r/branches/ref0")
        assertTrue(runCatching { gate.requireAvailable("/repos/o/r/branches/ref128") }.isFailure)
        gate.requireAvailable("/repos/o/r/commits/ref128")
        gate.requireAvailable("/repos/o/r/git/ref/tags/ref128")
    }

    @Test fun branchHeadRefreshClearsOnlyTheExactBranchObservation() {
        val gate = GitHubRefAvailability()
        gate.failed("/repos/o/r/git/ref/heads/feature", 404, "Not found")
        gate.failed("/repos/o/r/commits/feature", 404, "Not found")
        gate.failed("/repos/o/r/git/ref/heads/other", 404, "Not found")
        gate.failed("/repos/o/other/git/ref/heads/feature", 404, "Not found")
        gate.revalidateBranch("/repos/o/r", "feature")
        gate.requireAvailable("/repos/o/r/git/ref/heads/feature")
        assertTrue(runCatching { gate.requireAvailable("/repos/o/r/commits/feature") }.isFailure)
        assertTrue(runCatching { gate.requireAvailable("/repos/o/r/git/ref/heads/other") }.isFailure)
        assertTrue(runCatching { gate.requireAvailable("/repos/o/other/git/ref/heads/feature") }.isFailure)
    }

    @Test fun staleCommitStopsCascadingHttpAndExplicitRefreshCanRecover() = runTest {
        val paths = mutableListOf<String>()
        var restored = false
        val sha = "a".repeat(40)
        val tree = "b".repeat(40)
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/repos/o/r/commits/gone" -> if (restored) {
                        respond("""{"sha":"$sha","commit":{"tree":{"sha":"$tree"}},"files":[]}""")
                    } else {
                        respond("""{"message":"No commit found for SHA: gone"}""", HttpStatusCode.UnprocessableEntity)
                    }
                    "/repos/o/r/git/trees/$tree" -> {
                        assertTrue(restored)
                        assertEquals("1", request.url.parameters["recursive"])
                        respond("""{"sha":"$tree","tree":[],"truncated":false}""")
                    }
                    else -> error("Ref recovery must stay on the requested ref and its resolved tree: ${request.url}")
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http)
            val args = buildJsonObject {
                put("owner", "o")
                put("repo", "r")
                put("ref", "gone")
            }
            assertTrue(runCatching { client.execute("get_commit_detail", args) }.exceptionOrNull() is GitHubRequestRejected)
            assertTrue(runCatching { client.execute("get_commit_status_summary", args) }.exceptionOrNull() is GitHubReadValidation)
            assertEquals(listOf("/repos/o/r/commits/gone"), paths)
            assertNull(client.taskContext())
            restored = true
            client.execute("resolve_context", JsonObject(args + ("refresh" to JsonPrimitive(true))))
            assertEquals(listOf("/repos/o/r/commits/gone", "/repos/o/r/commits/gone", "/repos/o/r/git/trees/$tree"), paths)
            assertEquals("gone", client.taskContext()?.ref)
            assertEquals(sha, client.taskSnapshot()?.commitSha)
        } finally {
            http.close()
        }
    }

    @Test fun missingBranchDoesNotFanOutIntoPullRequestsAndWorkflowQueries() = runTest {
        val paths = mutableListOf<String>()
        val client = GitHubInspection { path ->
            paths += path
            throw GitHubRequestRejected(404, "Branch not found or inaccessible")
        }
        assertTrue(runCatching { client.execute("branch_dependencies", "/repos/o/r", Json.parseToJsonElement("""{"branch":"gone"}""").jsonObject, 1) }.isFailure)
        assertEquals(listOf("/repos/o/r/branches/gone"), paths)
        assertFalse(paths.any { "/pulls" in it || "/actions" in it })
    }
}
