package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPlanningPolicyTest {
    private val selected = GitHubRepositoryContext("actual-owner", "actual-repo", "working")
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun planningUsesTheSelectedExecutorTargetWithoutClaimingFreshAccessOrChangingIntent() {
        val context = GitHubPlanningContext("connection", "__selected", selected, "a".repeat(40), 100)
        val prompt = GitHubPlanningContext.forRequest("Test all GitHub integration tools", listOf(context))
        assertTrue(prompt.contains("\"owner\":\"actual-owner\""))
        assertTrue(prompt.contains("\"ref\":\"working\""))
        assertTrue(prompt.contains("\"context_tool\":\"github_context__selected\""))
        assertTrue(prompt.contains("\"access_verified_for_current_request\":false"))
        assertTrue(context.toolDescription().contains("actual-owner/actual-repo"))
        for (request in listOf("hello", "test", "just a test message", "test all providers", "Write a poem about trees")) {
            assertEquals(request, "", GitHubPlanningContext.forRequest(request, listOf(context)))
        }
        assertTrue(GitHubPlanningContext.forRequest("Continue", listOf(context)).isNotEmpty())
    }

    @Test fun missingAndFabricatedTargetsAreRejectedBeforeDispatchButSelectedAndExplicitTargetsRemainUsable() {
        val policy = GitHubTargetPolicy("Test the GitHub integration")
        val missing = policy.rejection("list_workflows", JsonObject(emptyMap()), null)!!
        assertEquals(JsonPrimitive("REPOSITORY_TARGET_REQUIRED"), missing["error_code"])
        assertEquals(JsonPrimitive(false), missing["dispatched"])
        val invented = policy.rejection("resolve_context", json("""{"owner":"github-workspace","repo":"test-repo"}"""), selected)!!
        assertEquals(JsonPrimitive("REPOSITORY_TARGET_UNVERIFIED"), invented["error_code"])
        assertEquals(JsonPrimitive("actual-owner/actual-repo"), invented["selected_repository"])
        assertNull(policy.rejection("list_workflows", json("""{"owner":"actual-owner","repo":"actual-repo"}"""), selected))
        assertNull(GitHubTargetPolicy("Inspect https://github.com/another/project.git").rejection("resolve_context", json("""{"owner":"another","repo":"project"}"""), selected))
        assertTrue(GitHubTargetPolicy("Inspect another/project-extra").rejection("resolve_context", json("""{"owner":"another","repo":"project"}"""), selected) != null)
    }

    @Test fun onlyAuthorizedRepositoryDiscoveryCanIntroduceAnotherTarget() {
        val policy = GitHubTargetPolicy("Find the repository and inspect it")
        val target = json("""{"owner":"observed","repo":"project"}""")
        val result = json("""{"items":[{"full_name":"observed/project"}]}""")
        policy.observeDiscovery("read_ranges", result)
        assertTrue(policy.rejection("resolve_context", target, selected) != null)
        policy.observeDiscovery("search_repositories", result)
        assertNull(policy.rejection("resolve_context", target, selected))
        assertNull(policy.rejection("search_repositories", JsonObject(emptyMap()), null))
        assertNull(policy.rejection("list_repositories", JsonObject(emptyMap()), null))
    }

    @Test fun oauthHeadersNeverBecomeAnUnsupportedPermissionDiagnosis() {
        val evidence = GitHubFailurePolicy.notFoundEvidence(
            "repository_details",
            json("""{"owner":"o","repo":"r","ref":"main"}"""),
            "/repos/o/r?secret=not-kept",
            "read:org,repo,workflow",
            "repo"
        )
        assertEquals(JsonPrimitive(false), evidence["resource_absence_verified"])
        assertEquals(JsonPrimitive(false), evidence["permission_deficit_verified"])
        assertEquals(JsonPrimitive("repo"), evidence["endpoint_accepted_oauth_scopes"])
        assertEquals(JsonPrimitive("read:org,repo,workflow"), evidence["reported_token_oauth_scopes"])
        assertEquals(JsonPrimitive("/repos/o/r"), evidence["endpoint"])
        assertFalse(evidence.toString().contains("secret"))
    }

    @Test fun exactRoot404PausesDependentOperationsUntilRefreshOrExpiryWithoutPoisoningOtherResources() {
        var clock = 0L
        val gate = GitHubRefAvailability(now = { clock }, cooldownMillis = 100)
        gate.failed("/repos/o/r/contents/missing", 404, "Not Found")
        gate.requireAvailable("/repos/o/r/actions/workflows")
        gate.failed("/repos/O/R", 404, "Not Found")
        val error = runCatching { gate.requireAvailable("/repos/o/r/actions/workflows") }.exceptionOrNull() as GitHubReadValidation
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", error.code)
        assertEquals(JsonPrimitive(false), error.details["dispatched"])
        gate.requireAvailable("/repos/o/another/actions/workflows")
        gate.revalidateRepository("/repos/o/r")
        gate.requireAvailable("/repos/o/r/commits/main")
        gate.failed("/repos/o/r", 404, "Not Found")
        clock = 100
        gate.requireAvailable("/repos/o/r")
        gate.failed("/repos/o/r", 404, "Not Found")
        gate.clear()
        gate.requireAvailable("/repos/o/r")
    }

    @Test fun capturedWorkflowPagesAreStableScopedAndExplicitlyRefreshable() {
        var clock = 0L
        val pages = GitHubWorkflowPageCapture(now = { clock }, wallClock = { 1000 })
        pages.remember("owner", "repo", 1, 30, "first page")
        pages.remember("owner", "repo", 2, 30, "second page")
        assertEquals("first page", pages.read("OWNER", "REPO", 1, 30, false)?.payload)
        assertEquals(1000L, pages.read("owner", "repo", 1, 30, false)?.observedAtMillis)
        assertNull(pages.read("owner", "other", 1, 30, false))
        assertNull(pages.read("owner", "repo", 1, 100, false))
        assertNull(pages.read("owner", "repo", 1, 30, true))
        assertNull(pages.read("owner", "repo", 2, 30, false))
        pages.remember("owner", "repo", 1, 30, "fresh page")
        assertEquals("fresh page", pages.read("owner", "repo", 1, 30, false)?.payload)
        clock = 300_001
        assertNull(pages.read("owner", "repo", 1, 30, false))
    }
}
