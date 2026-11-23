package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubNativeSearchContinuationTest {
    @Test fun nativeFacadeFinishesAZeroHitSearchUsingItsExactPinnedContinuation() = runBlocking {
        val head = "a".repeat(40)
        val tree = "b".repeat(40)
        val source = "fun unrelated() = 1"
        var blobs = 0
        val client = HttpClient(
            MockEngine { request ->
                val body = when {
                    "/commits/" in request.url.encodedPath -> buildJsonObject {
                        put("sha", head)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
                    }
                    "/git/trees/" in request.url.encodedPath -> buildJsonObject {
                        put("truncated", false)
                        put(
                            "tree",
                            JsonArray(
                                (1..2).map { index ->
                                    buildJsonObject {
                                        put("path", "src/File$index.kt")
                                        put("sha", "%040x".format(index))
                                        put("type", "blob")
                                        put("size", source.length)
                                    }
                                }
                            )
                        )
                    }
                    "/git/blobs/" in request.url.encodedPath -> {
                        blobs++
                        buildJsonObject {
                            put("encoding", "base64")
                            put("content", Base64.getEncoder().encodeToString(source.toByteArray()))
                        }
                    }
                    request.url.encodedPath == "/repos/owner/repo" -> buildJsonObject { put("default_branch", "main") }
                    else -> error("Unexpected search request: ${request.url.encodedPath}")
                }
                respond(body.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val backend = GitHubTool(apiToken = "fixture-token", httpClient = client, modelToolName = "github__native_search", repositoryContext = GitHubRepositoryContext("owner", "repo", "main"), persistentState = false)
            val tool = GitHubOperationTool("github_search", backend, "native-search-frontier", modelName = "github_search__native_search")
            val arguments = buildJsonObject {
                put("query", "needle")
                put("max_new_blobs", 1)
            }
            val firstCall = ProviderEvent.ToolCall("first", tool.definition.name, arguments)
            val first = tool.execute(firstCall.callId, arguments)
            assertFalse(first.content.toString(), first.isError)
            val frontier = requireNotNull(first.executionReceipt?.get("search_frontier") as? JsonObject)
            assertEquals(JsonPrimitive(false), frontier["complete"])
            assertEquals(JsonPrimitive(0), frontier["returned"])
            val exchanges = mutableListOf(AgentToolExchange(listOf(firstCall), listOf(first)))
            val state = GitHubReplayRecovery.State(listOf(firstCall))
            val next = requireNotNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state, allowSearchContinuation = true))
            assertEquals(JsonPrimitive(head), next.arguments["commit_sha"])
            assertEquals(frontier["search_id"], next.arguments["search_id"])
            val last = tool.execute(next.callId, next.arguments)
            assertFalse(last.content.toString(), last.isError)
            state.record(listOf(next))
            exchanges += AgentToolExchange(listOf(next), listOf(last))
            assertEquals(JsonPrimitive(true), (last.executionReceipt?.get("search_frontier") as? JsonObject)?.get("complete"))
            assertNull(GitHubReplayRecovery.nextRemoteRead(exchanges, listOf(tool.definition), state, allowSearchContinuation = true))
            assertEquals(2, blobs)
        } finally {
            client.close()
        }
    }
}
