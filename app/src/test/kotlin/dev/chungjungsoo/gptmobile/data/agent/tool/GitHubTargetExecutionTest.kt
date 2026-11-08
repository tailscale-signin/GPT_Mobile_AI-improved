package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTargetExecutionTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun aVerifiedBranchTransitionUpdatesPlanningAndExecutorDefaultsInsideTheBoundRepository() = runTest {
        val head = "c".repeat(40)
        val tree = "b".repeat(40)
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/commits/feature%2Fnew" -> respond("""{"sha":"$head","commit":{"tree":{"sha":"$tree"}}}""")
                    "/repos/o/r/git/trees/$tree" -> respond("""{"sha":"$tree","tree":[],"truncated":false}""")
                    "/repos/o/r/actions/workflows" -> respond("""{"total_count":0,"workflows":[]}""")
                    else -> error("A branch transition must stay inside the selected repository: ${request.url}")
                }
            }
        )
        try {
            val scope = GitHubConversationContext("connection", "o", "r", "main", "a".repeat(40), "profile")
            val tool = GitHubTool("token", http, repositoryScope = scope, persistentState = false, taskRequest = "Inspect the GitHub repository")
            val resolved = tool.execute("resolve", json("""{"action":"resolve_context","ref":"feature/new","refresh":true}"""))
            assertFalse(resolved.toString(), resolved.isError)
            val planning = tool.planningContext()
            assertEquals(GitHubRepositoryContext("o", "r", "feature/new"), planning.repository)
            assertEquals(head, planning.observedCommitSha)
            assertTrue(planning.observedAtMillis != null)
            val defaults = tool.execute("defaults", json("""{"action":"list_workflows"}"""))
            assertFalse(defaults.toString(), defaults.isError)
            assertEquals(JsonPrimitive("feature/new"), defaults.executionReceipt!!.getValue("resource_context").jsonObject["selected_ref"])
        } finally {
            http.close()
        }
    }

    @Test fun fabricatedTargetsNeverDispatchAndThePlannerAndExecutorShareTheWorkspaceSelection() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                respond("""{"total_count":0,"workflows":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val scope = GitHubConversationContext("connection", "actual", "selected", "working", "a".repeat(40), "profile")
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("stale", "other", "main"), repositoryScope = scope, persistentState = false, taskRequest = "Test the GitHub integration")
            assertEquals(scope.repository, tool.planningContext().repository)
            val ci = tool.operations().single { it.operation == "github_ci" }
            assertTrue(ci.definition.description.contains("actual/selected"))
            val invented = ci.execute("invented", json("""{"owner":"github-workspace","repo":"test-repo"}"""))
            assertTrue(invented.isError)
            assertFalse(invented.dispatched)
            assertTrue(requests.isEmpty())
            val selected = ci.execute("selected", JsonObject(emptyMap()))
            assertFalse(selected.toString(), selected.isError)
            assertEquals(listOf("/repos/actual/selected/actions/workflows"), requests)

            val ordinary = GitHubTool("token", http, repositoryContext = scope.repository, persistentState = false, taskRequest = "Test all GitHub tools")
            val rejected = ordinary.execute("invented-ordinary", json("""{"action":"repository_details","owner":"github-workspace","repo":"test-repo"}"""))
            assertEquals("REPOSITORY_TARGET_UNVERIFIED", rejected.errorCode)
            assertFalse(rejected.dispatched)
            assertEquals(1, requests.size)
        } finally {
            http.close()
        }
    }

    @Test fun repository404BlocksNativeFanoutAndExplicitRefreshRecoversWithoutInventingScopeDeficits() = runTest {
        val paths = mutableListOf<String>()
        var restored = false
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/repos/o/r" -> if (restored) {
                        respond("""{"full_name":"o/r","default_branch":"main"}""")
                    } else {
                        respond(
                            """{"message":"Not Found"}""",
                            HttpStatusCode.NotFound,
                            headersOf("X-OAuth-Scopes" to listOf("read:org,repo,workflow"), "X-Accepted-OAuth-Scopes" to listOf("repo"))
                        )
                    }
                    "/repos/o/r/languages" -> respond("""{"Kotlin":100}""")
                    "/repos/o/r/actions/workflows" -> respond("""{"total_count":0,"workflows":[]}""")
                    else -> error("Unexpected dependent request ${request.url}")
                }
            }
        )
        try {
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "main"), persistentState = false, taskRequest = "Test the GitHub integration")
            val rejected = tool.execute("repository", json("""{"action":"repository_details"}"""))
            assertEquals("GITHUB_NOT_FOUND_OR_INACCESSIBLE", rejected.errorCode)
            val failure = (rejected.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(false), failure["permission_deficit_verified"])
            assertEquals(JsonPrimitive("repo"), failure["endpoint_accepted_oauth_scopes"])
            val dependent = tool.execute("ci", json("""{"action":"list_workflows"}"""))
            assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", dependent.errorCode)
            assertFalse(dependent.dispatched)
            assertEquals(listOf("/repos/o/r"), paths)
            restored = true
            assertFalse(tool.execute("refresh", json("""{"action":"repository_details","refresh":true}""")).isError)
            assertFalse(tool.execute("ci-after-refresh", json("""{"action":"list_workflows"}""")).isError)
            assertEquals(listOf("/repos/o/r", "/repos/o/r", "/repos/o/r/languages", "/repos/o/r/actions/workflows"), paths)
        } finally {
            http.close()
        }
    }

    @Test fun defaultAndExplicitWorkflowAliasesReuseOrderedCaptureUntilDeliberateLiveRefresh() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                val rows = if (requests == 1) """[{"id":9,"name":"Z"},{"id":3,"name":"A"}]""" else """[{"id":5,"name":"New"},{"id":3,"name":"A"}]"""
                respond("""{"total_count":2,"workflows":$rows}""")
            }
        )
        try {
            val native = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "main"), persistentState = false, taskRequest = "List GitHub workflows")
            val ci = native.operations().single { it.operation == "github_ci" }
            fun ids(result: dev.chungjungsoo.gptmobile.data.agent.AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject.getValue("data").jsonObject.getValue("workflows").jsonArray.map { it.jsonObject["id"] }
            val first = ci.execute("default", buildJsonObject {})
            val alias = ci.execute("explicit", buildJsonObject { put("action", "workflows") })
            assertEquals(listOf(JsonPrimitive(3), JsonPrimitive(9)), ids(first))
            assertEquals(ids(first), ids(alias))
            assertEquals(1, requests)
            assertTrue(alias.sharedResult)
            assertFalse(alias.dispatched)
            assertEquals(JsonPrimitive(0), alias.executionReceipt?.get("read_requests"))
            val fresh = ci.execute(
                "refresh",
                buildJsonObject {
                    put("action", "workflows")
                    put("verify_live", true)
                }
            )
            assertEquals(listOf(JsonPrimitive(3), JsonPrimitive(5)), ids(fresh))
            assertEquals(2, requests)
            assertTrue(fresh.dispatched)
        } finally {
            http.close()
        }
    }
}
