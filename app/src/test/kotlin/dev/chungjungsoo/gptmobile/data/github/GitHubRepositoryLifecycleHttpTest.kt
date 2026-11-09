package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryLifecycleHttpTest {
    private fun arguments() = buildJsonObject {
        put("owner", "previous")
        put("repo", "unavailable")
        put("destination_owner", "owner")
        put("destination_repo", "new-project")
        put("owner_type", "user")
        put("visibility", "private")
    }

    @Test fun unavailableOpenRepositoryDoesNotBlockCreationAndRetainedPostIsNotRepeated() = runTest {
        val calls = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                calls += call
                when (call) {
                    "GET /repos/previous/unavailable" -> respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
                    "GET /user" -> respond("""{"login":"owner"}""")
                    "POST /user/repos" -> respond("""{"id":91}""", HttpStatusCode.Created)
                    "GET /repos/owner/new-project" -> respond("""{"id":91,"full_name":"owner/new-project","private":true,"fork":false,"default_branch":"main"}""")
                    else -> error("Unexpected lifecycle request $call")
                }
            }
        )
        try {
            val gate = GitHubRefAvailability()
            val client = GitHubWorkspaceClient("token", http, refAvailability = gate, conversationId = "create-conversation")
            assertTrue(runCatching { client.execute("get_repository", arguments()) }.isFailure)
            repeat(2) {
                val created = client.execute("create_repository", arguments()).jsonObject
                assertEquals(JsonPrimitive("VERIFIED"), created["status"])
                assertEquals(JsonPrimitive(91), created["repository_id"])
                assertEquals(JsonPrimitive("owner/new-project"), created["destination_repository"])
                assertEquals(JsonPrimitive(false), created["workspace_selection_changed"])
            }
            assertEquals(1, calls.count { it == "POST /user/repos" })
            assertEquals(1, calls.count { it == "GET /repos/previous/unavailable" })
            assertTrue(runCatching { gate.requireAvailable("/repos/previous/unavailable") }.isFailure)
        } finally {
            http.close()
        }
    }

    @Test fun nativeCreationFacadeKeepsItsExplicitDestinationOutOfTheUnavailableWorkspaceScope() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when ("${request.method.value} ${request.url.encodedPath}") {
                    "GET /repos/previous/unavailable" -> respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
                    "GET /user" -> respond("""{"login":"owner"}""")
                    "POST /user/repos" -> respond("""{"id":91}""", HttpStatusCode.Created)
                    "GET /repos/owner/new-project" -> respond("""{"id":91,"full_name":"owner/new-project","private":true,"fork":false,"default_branch":"main"}""")
                    else -> error("Unexpected native lifecycle request ${request.url}")
                }
            }
        )
        try {
            val scope = GitHubConversationContext("connection", "previous", "unavailable", "main", "a".repeat(40), "profile")
            val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool("token", http, repositoryScope = scope, persistentState = false, taskRequest = "Create a private repository named new-project under owner")
            assertTrue(tool.execute("old-target", buildJsonObject { put("action", "repository_details") }).isError)
            val facade = tool.operations().single { it.operation == "github_repository" }
            val create = JsonObject(arguments().filterKeys { it !in setOf("owner", "repo") } + ("action" to JsonPrimitive("create")))
            val result = facade.execute("create", create)
            assertFalse(result.toString(), result.isError)
            val context = requireNotNull(result.executionReceipt?.get("resource_context") as? JsonObject)
            assertEquals(JsonPrimitive("owner"), context["destination_owner"])
            assertEquals(JsonPrimitive("new-project"), context["destination_repo"])
            assertTrue(context.keys.none { it in setOf("owner", "repo", "ref", "selected_ref") })
            assertEquals(scope.repository, tool.planningContext().repository)
            assertEquals(listOf("/repos/previous/unavailable", "/user", "/user/repos", "/repos/owner/new-project"), paths)
        } finally {
            http.close()
        }
    }

    @Test fun lostCreateResponseKeepsItsFenceAcrossTaskAndClientReplacement() = runTest {
        var creates = 0
        var destinationReads = 0
        val http = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath == "/user" -> respond("""{"login":"owner"}""")
                    request.method == HttpMethod.Post && request.url.encodedPath == "/user/repos" -> {
                        creates++
                        throw IOException("Response lost after request dispatch")
                    }
                    request.url.encodedPath == "/repos/owner/new-project" -> {
                        destinationReads++
                        respond("""{"id":91,"full_name":"owner/new-project","private":true,"fork":false}""")
                    }
                    else -> error("Unexpected request ${request.url}")
                }
            }
        )
        try {
            val state = GitHubRepositoryStateStore()
            fun client(task: String) = GitHubWorkspaceClient("token", http, connectionUid = "connection", conversationId = "recovery-conversation", taskId = task, stateStore = state)
            assertTrue(runCatching { client("original").execute("create_repository", arguments()) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertTrue(runCatching { client("continuation").execute("create_repository", arguments()) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(1, creates)
            assertEquals("An unrelated repository's existence cannot replace the lost creation receipt", 0, destinationReads)
        } finally {
            http.close()
        }
    }

    @Test fun verifiedCreationKeepsCompoundImplementationBehindAnExplicitRepositorySelection() = runTest {
        var creates = 0
        val http = HttpClient(
            MockEngine { request ->
                when ("${request.method.value} ${request.url.encodedPath}") {
                    "GET /user" -> respond("""{"login":"owner"}""")
                    "POST /user/repos" -> {
                        creates++
                        respond("""{"id":91}""", HttpStatusCode.Created)
                    }
                    "GET /repos/owner/new-project" -> respond("""{"id":91,"full_name":"owner/new-project","private":true,"fork":false,"default_branch":"main"}""")
                    else -> error("Unexpected request ${request.url}")
                }
            }
        )
        try {
            val selected = GitHubRepositoryContext("previous", "workspace", "main")
            val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool(
                "token",
                http,
                repositoryContext = selected,
                persistentState = false,
                taskRequest = "Create a private repository named new-project under owner, then implement and test a feature in it"
            )
            val request = JsonObject(arguments().filterKeys { it !in setOf("owner", "repo") } + ("action" to JsonPrimitive("create_repository")))
            val result = tool.execute("create-compound", request)
            assertFalse(result.toString(), result.isError)
            val body = when (val content = result.retainedContent ?: result.content) {
                is dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json -> content.value.jsonObject
                is dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text -> kotlinx.serialization.json.Json.parseToJsonElement(content.text).jsonObject
                else -> error("Creation must retain its structured receipt")
            }
            assertEquals(JsonPrimitive("VERIFIED"), body["status"])
            assertEquals(JsonPrimitive(true), body["user_input_required"])
            assertEquals(JsonPrimitive(false), body["task_complete"])
            assertEquals(JsonPrimitive("CHANGE_REPOSITORY"), body["next_action"]?.jsonObject?.get("user_action"))
            assertEquals(JsonPrimitive("owner/new-project"), body["next_action"]?.jsonObject?.get("destination_repository"))
            assertEquals(selected, tool.planningContext().repository)
            assertEquals(1, creates)
        } finally {
            http.close()
        }
    }

    @Test fun asynchronousForkStatusRefreshesOnlyItsDestinationAndNeverForksAgain() = runTest {
        var creates = 0
        var destinationReads = 0
        val http = HttpClient(
            MockEngine { request ->
                when ("${request.method.value} ${request.url.encodedPath}") {
                    "GET /repos/source/project" -> respond("""{"id":1,"full_name":"source/project","private":true}""")
                    "GET /user" -> respond("""{"login":"owner"}""")
                    "POST /repos/source/project/forks" -> {
                        creates++
                        respond("""{"id":92}""", HttpStatusCode.Accepted)
                    }
                    "GET /repos/owner/new-project" -> {
                        destinationReads++
                        if (destinationReads == 1) {
                            respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
                        } else {
                            respond("""{"id":92,"full_name":"owner/new-project","private":true,"fork":true,"parent":{"id":1,"full_name":"source/project"},"default_branch":"main"}""")
                        }
                    }
                    else -> error("Unexpected request ${request.url}")
                }
            }
        )
        try {
            val gate = GitHubRefAvailability()
            gate.failed("/repos/other/unavailable", 404, "Not found")
            val client = GitHubWorkspaceClient("token", http, refAvailability = gate, conversationId = "fork-conversation")
            val args = JsonObject(
                arguments() + buildJsonObject {
                    put("source_owner", "source")
                    put("source_repo", "project")
                }
            )
            val pending = client.execute("fork_repository", args).jsonObject
            assertEquals(JsonPrimitive("PENDING"), pending["status"])
            assertFalse(pending["remote_state_verified"] == JsonPrimitive(true))
            val status = client.execute(
                "repository_lifecycle_status",
                JsonObject(
                    args + buildJsonObject {
                        put("operation", "fork_repository")
                        put("expected_repository_id", 92)
                    }
                )
            ).jsonObject
            assertEquals(JsonPrimitive("VERIFIED"), status["status"])
            assertEquals(JsonPrimitive(92), status["repository_id"])
            assertEquals(1, creates)
            assertEquals(2, destinationReads)
            assertTrue(runCatching { gate.requireAvailable("/repos/other/unavailable") }.isFailure)
        } finally {
            http.close()
        }
    }

    @Test fun anInventedCreationIdCannotTurnAnExistingRepositoryIntoVerifiedTaskCompletion() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("""{"id":91,"full_name":"owner/new-project","private":true,"fork":false}""")
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http, conversationId = "no-creation-receipt")
            val args = JsonObject(
                arguments() + buildJsonObject {
                    put("operation", "create_repository")
                    put("expected_repository_id", 91)
                }
            )
            val failure = runCatching { client.execute("repository_lifecycle_status", args) }.exceptionOrNull()
            assertTrue("Status needs this conversation's retained creation receipt", failure != null)
            assertEquals("Reject unproven creation identity before an irrelevant existence lookup", 0, requests)
        } finally {
            http.close()
        }
    }

    @Test fun gatewayTransferQualifiesTheCurrentAccountAndReplacesCallerSuppliedActorIdentity() = runTest {
        var actor = 42
        val payloads = mutableListOf<JsonObject>()
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                assertEquals("/user", request.url.encodedPath)
                respond("""{"id":$actor,"login":"owner"}""")
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http, repositoryGateway = { payload ->
                payloads += payload
                buildJsonObject { put("status", "PENDING") }
            })
            val args = buildJsonObject {
                put("source_owner", "upstream")
                put("source_repo", "project")
                put("source_ref", "main")
                put("expected_actor_id", 999)
            }
            client.execute("clone_repository", args)
            assertEquals(JsonPrimitive(42), payloads.single()["expected_actor_id"])
            assertEquals(JsonPrimitive("clone_repository"), payloads.single()["action"])
            actor = 0
            assertTrue(runCatching { client.execute("clone_repository", args) }.isFailure)
            assertEquals("An invalid current account cannot reach the host transfer", 1, payloads.size)
            assertEquals(listOf("/user", "/user"), paths)
        } finally {
            http.close()
        }
    }
}
