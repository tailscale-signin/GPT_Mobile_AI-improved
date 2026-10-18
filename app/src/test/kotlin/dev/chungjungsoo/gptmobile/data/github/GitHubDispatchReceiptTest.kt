package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDispatchReceiptTest {
    private val sha = "a".repeat(40)
    private fun args(run: Long? = null, tag: String = "v1.2.3") = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("tag_name", tag)
        put("ref", "main")
        put("expected_head_sha", sha)
        run?.let { put("workflow_run_id", it) }
    }
    private inner class Fixture(private val ids: List<Long?>, var runHead: String = sha) : AutoCloseable {
        var dispatches = 0
        val runsRead = mutableListOf<Long>()
        val http = HttpClient(
            MockEngine { request ->
                when (val path = request.url.encodedPath) {
                    "/repos/owner/repo/releases" -> respond("[]")
                    "/repos/owner/repo/actions/workflows" -> respond("""{"total_count":1,"workflows":[{"id":42,"name":"Publish Signed Release","path":".github/workflows/release-build.yml","state":"active"}]}""")
                    "/repos/owner/repo/actions/workflows/42" -> respond("""{"id":42,"name":"Publish Signed Release","state":"active"}""")
                    "/repos/owner/repo/commits/main" -> respond("""{"sha":"$sha"}""")
                    "/repos/owner/repo/contents/app/build.gradle.kts" -> {
                        assertEquals(sha, request.url.parameters["ref"])
                        val encoded = Base64.getEncoder().encodeToString("versionName = \"1.2.3\"".toByteArray())
                        respond("""{"type":"file","encoding":"base64","content":"$encoded"}""")
                    }
                    "/repos/owner/repo/actions/workflows/42/dispatches" -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertTrue(body["return_run_details"]!!.jsonPrimitive.boolean)
                        val id = ids[dispatches++]
                        if (id == null) respond("", HttpStatusCode.NoContent) else respond("""{"workflow_run_id":$id,"run_url":"https://api.github.com/repos/owner/repo/actions/runs/$id","html_url":"https://github.com/owner/repo/actions/runs/$id"}""")
                    }
                    else -> {
                        check(path.startsWith("/repos/owner/repo/actions/runs/")) { "Unexpected request: ${request.url}" }
                        val id = path.substringAfterLast('/').toLong()
                        runsRead += id
                        respond("""{"id":$id,"workflow_id":42,"head_sha":"$runHead","event":"workflow_dispatch","head_branch":"main","status":"completed","conclusion":"success"}""")
                    }
                }
            }
        )
        val client = GitHubWorkspaceClient("token", http)
        override fun close() = http.close()
        suspend fun dispatch(): JsonObject = client.execute("publish_release", args()).jsonObject
    }

    @Test fun twoDispatchesOnTheSameBranchAndSourceKeepTheirOwnExactRuns() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            val first = fixture.dispatch()
            val second = fixture.dispatch()
            assertEquals("501", first["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            assertEquals("502", second["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            for (run in listOf(501L, 502L)) {
                val status = fixture.client.execute("release_status", args(run)).jsonObject
                assertEquals("DISPATCH_RECEIPT_AND_SOURCE_VERIFIED", status["workflow_correlation"]!!.jsonPrimitive.content)
                assertEquals(run.toString(), status["latest_workflow_run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertFalse(status["release_asset_source_verified"]!!.jsonPrimitive.boolean)
            }
            assertEquals(listOf(501L, 502L), fixture.runsRead)
            assertTrue(runCatching { fixture.client.execute("release_status", args(501, "v9.9.9")) }.isFailure)
        }
    }

    @Test fun acceptedDispatchWithoutRunReceiptRemainsUnknownAndIsNotRedispatched() = runTest {
        Fixture(listOf(null)).use { fixture ->
            val result = fixture.dispatch()
            assertEquals("UNKNOWN_NO_RUN_RECEIPT_RETURNED", result["dispatch_receipt"]!!.jsonObject["run_correlation"]!!.jsonPrimitive.content)
            val status = fixture.client.execute("release_status", args()).jsonObject
            assertEquals("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE", status["workflow_correlation"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, status["latest_workflow_run"])
            assertEquals(1, fixture.dispatches)
            assertTrue(fixture.runsRead.isEmpty())
        }
    }

    @Test fun olderRunWithMatchingBranchSourceAndWorkflowCannotEstablishDispatchCorrelation() = runTest {
        Fixture(emptyList()).use { fixture ->
            val status = fixture.client.execute("release_status", args(999)).jsonObject
            assertEquals("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE", status["workflow_correlation"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, status["latest_workflow_run"])
            assertEquals("999", status["candidate_workflow_run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun dispatchedRunWithChangedSourceIsRejected() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            fixture.dispatch()
            fixture.runHead = "b".repeat(40)
            assertTrue(runCatching { fixture.client.execute("release_status", args(501)) }.isFailure)
        }
    }
}
