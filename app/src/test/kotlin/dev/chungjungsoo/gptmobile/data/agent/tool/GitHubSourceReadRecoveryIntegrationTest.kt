package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSourceReadRecoveryIntegrationTest {
    private val head = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val blob = "c".repeat(40)

    private inner class Fixture(val paths: List<String>, val revokeAfterTree: Boolean = false, val policyChange: Boolean = false) {
        val requests = mutableListOf<String>()
        var revoked = false
        var credentialChecks = 0
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                val path = request.url.encodedPath
                requests += path
                when {
                    path.endsWith("/commits/$head") -> respond("""{"sha":"$head","commit":{"tree":{"sha":"$tree"}}}""")
                    path.endsWith("/git/trees/$tree") -> {
                        if (revokeAfterTree) revoked = true
                        respond(
                            buildJsonObject {
                                put("truncated", false)
                                put(
                                    "tree",
                                    JsonArray(
                                        paths.map { name ->
                                            buildJsonObject {
                                                put("path", name)
                                                put("sha", blob)
                                                put("type", "blob")
                                                put("mode", "100644")
                                                put("size", 16)
                                            }
                                        }
                                    )
                                )
                            }.toString()
                        )
                    }
                    path.endsWith("/git/blobs/$blob") -> respond("""{"sha":"$blob","encoding":"base64","content":"${Base64.getEncoder().encodeToString("retained source\n".toByteArray())}"}""")
                    else -> error("Unexpected request $path")
                }
            }
        )
        val backend = GitHubTool(
            apiToken = "test-token",
            httpClient = http,
            repositoryContext = GitHubRepositoryContext("owner", "repo", "main"),
            persistentState = false,
            credentialProvider = {
                credentialChecks++
                if (revoked && policyChange) throw dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException(listOf("github.connection"))
                check(!revoked) { "GitHub account changed" }
                "test-token"
            }
        )
        fun tool(operation: String) = GitHubOperationTool(operation, backend, "source-recovery-${paths.hashCode()}-$revokeAfterTree")
    }

    private fun read(path: String) = buildJsonObject {
        put("commit_sha", head)
        put(
            "ranges",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", path)
                        put("start_line", 1)
                        put("end_line", 20)
                    }
                )
            )
        )
    }

    private fun data(result: AgentToolResult): JsonObject {
        val body = ToolResultEnvelope.element(result.retainedContent ?: result.content).jsonObject
        return (body["data"] as? JsonObject) ?: body
    }

    @Test fun uniquePathIsCorrectedOnceWithTheSameCallIdentityAndExactDispatchTotals() = runTest {
        val fixture = Fixture(listOf("src/Main.kt"))
        try {
            val result = fixture.tool("github_read").execute("provider-read-1", read("old/Main.kt"))
            assertFalse(result.toString(), result.isError)
            assertEquals("provider-read-1", result.callId)
            assertEquals(JsonPrimitive("src/Main.kt"), data(result).getValue("items").jsonArray.single().jsonObject["path"])
            val receipt = requireNotNull(result.executionReceipt)
            val correction = receipt.getValue("source_read_recovery").jsonObject
            assertEquals(JsonPrimitive("provider-read-1"), correction["call_id"])
            assertEquals(JsonPrimitive(1), correction["attempts"])
            assertEquals(JsonPrimitive("GITHUB_PATH_NOT_FOUND"), correction["original_error_code"])
            assertEquals(JsonPrimitive(true), correction["successful"])
            assertEquals(fixture.requests.size, receipt.getValue("read_requests").jsonPrimitive.int)
            assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
            assertEquals(1, fixture.requests.count { "/git/blobs/" in it })
            assertEquals(JsonPrimitive("old/Main.kt"), receipt.getValue("original_arguments").jsonObject.getValue("ranges").jsonArray.single().jsonObject["path"])
            assertEquals(JsonPrimitive("src/Main.kt"), receipt.getValue("corrected_arguments").jsonObject.getValue("ranges").jsonArray.single().jsonObject["path"])
            assertEquals(JsonPrimitive("src/Main.kt"), receipt.getValue("resolved_arguments").jsonObject.getValue("ranges").jsonArray.single().jsonObject["path"])
        } finally {
            fixture.http.close()
        }
    }

    @Test fun revokedAuthorizationStopsTheCorrectionBeforeAnyBlobRead() = runTest {
        val fixture = Fixture(listOf("src/Main.kt"), revokeAfterTree = true)
        try {
            val result = fixture.tool("github_read").execute("provider-revoked", read("old/Main.kt"))
            assertTrue(result.isError)
            assertEquals("GITHUB_AUTHORIZATION_UNVERIFIED", result.errorCode)
            assertFalse(fixture.requests.any { "/git/blobs/" in it })
            val correction = result.executionReceipt!!.getValue("source_read_recovery").jsonObject
            assertEquals(JsonPrimitive(1), correction["attempts"])
            assertEquals(JsonPrimitive(false), correction["successful"])
            assertEquals("provider-revoked", result.callId)
        } finally {
            fixture.http.close()
        }
    }

    @Test fun changedDispatchPolicyRetainsItsTypedInvalidationOnTheCorrectedRead() = runTest {
        val fixture = Fixture(listOf("src/Main.kt"), revokeAfterTree = true, policyChange = true)
        try {
            val result = fixture.tool("github_read").execute("provider-policy-changed", read("old/Main.kt"))
            assertTrue(result.isError)
            assertEquals("DISPATCH_POLICY_CHANGED", result.errorCode)
            assertEquals(JsonPrimitive("DISPATCH_POLICY_CHANGED"), result.executionReceipt?.get("authority_invalidation_code"))
            assertFalse(fixture.requests.any { "/git/blobs/" in it })
            assertEquals(JsonPrimitive(1), result.executionReceipt!!.getValue("source_read_recovery").jsonObject["attempts"])
        } finally {
            fixture.http.close()
        }
    }

    @Test fun ambiguousCandidatesNeverStartAnAutomaticSourceRead() = runTest {
        val fixture = Fixture(listOf("src/Main.kt", "test/Main.kt"))
        try {
            val result = fixture.tool("github_read").execute("ambiguous", read("old/Main.kt"))
            assertTrue(result.isError)
            assertEquals("GITHUB_PATH_NOT_FOUND", result.errorCode)
            assertFalse(fixture.requests.any { "/git/blobs/" in it })
            assertFalse(result.executionReceipt.orEmpty().containsKey("source_read_recovery"))
        } finally {
            fixture.http.close()
        }
    }

    @Test fun omittedSearchBindingContinuesTheIssuedPageWithoutRestartingTheSearch() = runTest {
        val fixture = Fixture(listOf("A.kt", "B.kt", "C.kt", "D.kt"))
        try {
            val tool = fixture.tool("github_search")
            val first = tool.execute(
                "search-start",
                buildJsonObject {
                    put("query", "*")
                    put("mode", "paths")
                    put("commit_sha", head)
                    put("batch_size", 2)
                }
            )
            assertFalse(first.toString(), first.isError)
            val envelope = ToolResultEnvelope.element(first.content).jsonObject
            assertEquals(JsonPrimitive(true), envelope["local_page_complete"])
            val continuation = envelope.getValue("continuation_call").jsonObject
            assertEquals(JsonPrimitive(tool.definition.name), continuation["tool"])
            val issued = continuation.getValue("arguments").jsonObject
            assertEquals(data(first)["search_id"], issued["search_id"])
            val next = tool.execute("provider-search-page-2", JsonObject(issued - "search_id"))
            assertFalse(next.toString(), next.isError)
            assertEquals("provider-search-page-2", next.callId)
            assertEquals(JsonPrimitive(2), data(next)["interval_start"])
            assertEquals(issued["search_id"], data(next)["search_id"])
            assertEquals(JsonPrimitive("GITHUB_SEARCH_CURSOR_UNBOUND"), next.executionReceipt!!.getValue("source_read_recovery").jsonObject["original_error_code"])
            assertFalse(fixture.requests.any { "/git/blobs/" in it })
        } finally {
            fixture.http.close()
        }
    }
}
