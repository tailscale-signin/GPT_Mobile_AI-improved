package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGraphRevisionAuthorityTest {
    private val original = "a".repeat(40)
    private val current = "b".repeat(40)
    private val tree = "c".repeat(40)
    private val blob = "d".repeat(40)
    private val bound = GitHubConversationContext("connection", "owner", "repo", "main", original, "profile")

    private fun client(onRequest: (String) -> Unit = {}) = HttpClient(
        MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            val path = request.url.encodedPath
            onRequest(path)
            val body = when {
                "/commits/" in path -> {
                    val requested = path.substringAfterLast('/')
                    val sha = if (requested in setOf("main", "feature")) current else requested
                    """{"sha":"$sha","commit":{"tree":{"sha":"$tree"}}}"""
                }
                "/git/trees/" in path -> """{"tree":[{"path":"src/File.kt","type":"blob","mode":"100644","sha":"$blob","size":20}],"truncated":false}"""
                "/git/blobs/" in path -> """{"sha":"$blob","encoding":"base64","content":"${Base64.getEncoder().encodeToString("fun observed() = 1\n".toByteArray())}"}"""
                else -> error("Unexpected repository request $path")
            }
            respond(body)
        }
    )

    private suspend fun resolve(tool: GitHubTool, ref: String = "main"): AgentToolResult {
        val result = tool.execute(
            "resolve-$ref",
            buildJsonObject {
                put("action", "resolve_context")
                put("ref", ref)
                put("refresh", true)
            }
        )
        assertFalse(result.toString(), result.isError)
        return result
    }

    @Test fun firstCurrentPhoneMapResolvesItsBranchAndReusesTheRevisionAcrossReads() = runTest {
        for (firstAction in listOf("status", "index")) {
            val requests = mutableListOf<String>()
            client(requests::add).use { http ->
                val scopes = mutableListOf<String>()
                val tool = GitHubTool(
                    "token", http, connectionUid = bound.connectionUid, conversationId = "fresh-$firstAction",
                    taskId = "current", persistentState = false, repositoryScope = bound, taskRequest = "Inspect the latest branch map",
                    graphQuery = { scope, _ ->
                        scopes += scope.commitSha
                        buildJsonObject { put("source_head_sha", scope.commitSha) }
                    }
                )
                val graph = requireNotNull(tool.graphTool())
                for (action in listOf(firstAction, "status")) {
                    val result = graph.execute("map-${scopes.size}", buildJsonObject { put("action", action) })
                    assertFalse(result.toString(), result.isError)
                }
                assertEquals(listOf(current, current), scopes)
                assertEquals(1, requests.count { it.endsWith("/commits/main") })
            }
        }
    }

    @Test fun nativeRefreshSupersedesTheChatBindingForTheNextGraphRead() = runTest {
        client().use { http ->
            val observed = mutableListOf<GitHubConversationContext>()
            val tool = GitHubTool(
                "token",
                http,
                repositoryScope = bound,
                connectionUid = bound.connectionUid,
                conversationId = "conversation",
                taskId = "task",
                persistentState = false,
                graphQuery = { scope, _ ->
                    observed += scope
                    buildJsonObject { put("source_head_sha", scope.commitSha) }
                }
            )
            val graph = requireNotNull(tool.graphTool())
            assertFalse(graph.execute("initial-map", buildJsonObject { put("action", "status") }).isError)
            resolve(tool)
            val result = graph.execute("current-map", buildJsonObject { put("action", "status") })
            assertFalse(result.toString(), result.isError)
            assertEquals(listOf(original, current), observed.map { it.commitSha })
            assertEquals(current, tool.planningContext().observedCommitSha)
            assertEquals(JsonPrimitive(current), (result.content as ToolResultContent.Json).value.jsonObject["source_head_sha"])
            assertTrue(observed.all { it.connectionUid == bound.connectionUid && it.mainProfileUid == bound.mainProfileUid })
        }
    }

    @Test fun historicalSourceReadsDoNotRestoreAnOldMapRevision() = runTest {
        client().use { http ->
            var observed: GitHubConversationContext? = null
            val tool = GitHubTool(
                "token",
                http,
                repositoryScope = bound,
                connectionUid = bound.connectionUid,
                conversationId = "conversation",
                taskId = "task",
                persistentState = false,
                graphQuery = { scope, _ ->
                    observed = scope
                    buildJsonObject { put("source_head_sha", scope.commitSha) }
                }
            )
            resolve(tool, "feature")
            val historical = tool.execute(
                "historical-source",
                Json.parseToJsonElement("""{"action":"read_ranges","commit_sha":"$original","ranges":[{"path":"src/File.kt","start_line":1,"end_line":1}]}""").jsonObject
            )
            assertFalse(historical.toString(), historical.isError)
            val result = requireNotNull(tool.graphTool()).execute("current-map", buildJsonObject { put("action", "status") })
            assertFalse(result.toString(), result.isError)
            assertEquals("feature", observed?.ref)
            assertEquals(current, observed?.commitSha)
        }
    }

    @Test fun theFirstHistoricalReadDoesNotSelectItsCommitAsTheTaskRevision() = runTest {
        client().use { http ->
            var observed: GitHubConversationContext? = null
            val binding = bound.copy(commitSha = current)
            val tool = GitHubTool(
                "token",
                http,
                repositoryScope = binding,
                connectionUid = binding.connectionUid,
                conversationId = "fresh-conversation",
                taskId = "fresh-task",
                persistentState = false,
                graphQuery = { scope, _ ->
                    observed = scope
                    buildJsonObject { put("source_head_sha", scope.commitSha) }
                }
            )
            for (refresh in listOf(false, true)) {
                val historical = tool.execute(
                    "historical-first-$refresh",
                    Json.parseToJsonElement("""{"action":"read_ranges","commit_sha":"$original","refresh":$refresh,"ranges":[{"path":"src/File.kt","start_line":1,"end_line":1}]}""").jsonObject
                )
                assertFalse(historical.toString(), historical.isError)
                val result = requireNotNull(tool.graphTool()).execute("current-map-$refresh", buildJsonObject { put("action", "status") })
                assertFalse(result.toString(), result.isError)
                assertEquals("main", observed?.ref)
                assertEquals(current, observed?.commitSha)
                assertEquals(current, tool.planningContext().observedCommitSha)
            }
        }
    }

    @Test fun aCompletedGatewayMutationKeepsItsReceiptWhenTheTaskRevisionChanges() = runTest {
        for (action in listOf("index", "note")) {
            client().use { http ->
                lateinit var tool: GitHubTool
                val checkpoint = buildJsonObject {
                    put("index_outcome_unknown", true)
                    put("graph_index_attempt_hash", "e".repeat(64))
                }
                tool = GitHubTool(
                    "token",
                    http,
                    repositoryScope = bound,
                    connectionUid = bound.connectionUid,
                    conversationId = "conversation",
                    taskId = "task",
                    persistentState = false,
                    graphQuery = { scope, _ ->
                        assertEquals(original, scope.commitSha)
                        resolve(tool)
                        buildJsonObject {
                            put("operation_id", "observed-operation")
                            if (action == "index") put("index_checkpoint", checkpoint)
                            put("source_current", true)
                            put(
                                "task_context",
                                buildJsonObject {
                                    put("source_current", true)
                                    put("source_sha", scope.commitSha)
                                }
                            )
                        }
                    }
                )
                val result = requireNotNull(tool.graphTool()).execute(
                    "racing-$action",
                    buildJsonObject {
                        put("action", action)
                        put("map_source", "gateway")
                    }
                )
                assertFalse(result.toString(), result.isError)
                assertTrue(result.dispatched)
                val observed = (result.content as ToolResultContent.Json).value.jsonObject
                assertEquals(JsonPrimitive("observed-operation"), observed["operation_id"])
                assertEquals(JsonPrimitive(false), observed["source_current"])
                assertEquals(JsonPrimitive(false), observed.getValue("task_context").jsonObject["source_current"])
                assertEquals(JsonPrimitive(original), observed.getValue("task_context").jsonObject["source_sha"])
                assertEquals(JsonPrimitive(original), observed["requested_head_sha"])
                assertEquals(JsonPrimitive(current), observed["current_task_head_sha"])
                if (action == "index") {
                    assertEquals(checkpoint, observed["index_checkpoint"])
                    assertEquals(JsonPrimitive(true), result.executionReceipt?.get("index_outcome_unknown"))
                    assertEquals(JsonPrimitive("e".repeat(64)), result.executionReceipt?.get("graph_index_attempt_hash"))
                }
            }
        }
    }

    @Test fun aConcurrentNativeRevisionChangeRejectsTheFinishedMap() = runTest {
        for (mapRead in listOf(false, true)) {
            val requests = mutableListOf<String>()
            val mapPath = "/repos/owner/repo/git/trees/$tree"
            var resolveReceipt: JsonObject? = null
            client(requests::add).use { http ->
                lateinit var tool: GitHubTool
                tool = GitHubTool(
                    "token",
                    http,
                    repositoryScope = bound,
                    connectionUid = bound.connectionUid,
                    conversationId = "conversation",
                    taskId = "task",
                    persistentState = false,
                    graphQuery = { scope, _ ->
                        assertEquals(original, scope.commitSha)
                        if (mapRead) {
                            val requestId = GitHubDispatchAccounting.started(false, mapPath)
                            val response = http.get("https://api.github.com$mapPath")
                            GitHubDispatchAccounting.responded(false, response.status.value, requestId)
                            assertTrue(response.bodyAsText().contains("src/File.kt"))
                        }
                        resolveReceipt = resolve(tool).executionReceipt
                        buildJsonObject {
                            put("source_current", true)
                            put("source_head_sha", scope.commitSha)
                        }
                    }
                )
                val result = requireNotNull(tool.graphTool()).execute("racing-map", buildJsonObject { put("action", "status") })
                assertTrue(result.toString(), result.isError)
                assertEquals("GRAPH_REVISION_MISMATCH", result.errorCode)
                if (mapRead) assertTrue(result.dispatched) else assertFalse("A local map cannot borrow the nested resolve's dispatch", result.dispatched)
                assertEquals(JsonPrimitive(if (mapRead) 1 else 0), result.executionReceipt?.get("read_requests"))
                assertEquals(JsonPrimitive(if (mapRead) 1 else 0), result.executionReceipt?.get("read_response_observations"))
                assertEquals(JsonPrimitive(0), result.executionReceipt?.get("mutation_requests"))
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                // Native resolve reads the branch head, then its tree to materialize index status.
                assertEquals(JsonPrimitive(2), resolveReceipt?.get("read_requests"))
                assertEquals(JsonPrimitive(2), resolveReceipt?.get("read_response_observations"))
                assertEquals(JsonPrimitive(0), resolveReceipt?.get("mutation_requests"))
                assertEquals(JsonPrimitive(false), resolveReceipt?.get("requires_reconciliation"))
                assertEquals((if (mapRead) listOf(mapPath) else emptyList()) + listOf("/repos/owner/repo/commits/main", mapPath), requests)
                assertFalse((result.content as ToolResultContent.Json).value.jsonObject.containsKey("source_current"))
            }
        }
    }
}
