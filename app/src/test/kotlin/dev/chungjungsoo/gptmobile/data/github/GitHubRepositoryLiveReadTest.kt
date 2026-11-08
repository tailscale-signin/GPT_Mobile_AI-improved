package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryLiveReadTest {
    @Test fun forcedWorkflowVerificationBypassesMetadataTreeAndBlobCaches() = runTest {
        val fixture = Fixture()
        fixture.execute("workflow_catalog")
        assertEquals(3, fixture.requests.size)
        val cached = fixture.execute("workflow_catalog")
        assertEquals(3, fixture.requests.size)
        assertEquals(JsonPrimitive(true), cached.getValue("workflows").jsonArray.single().jsonObject["cache_hit"])
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        val live = withContext(accounting) { fixture.execute("workflow_catalog") }
        assertEquals(6, fixture.requests.size)
        assertEquals(JsonPrimitive(false), live.getValue("workflows").jsonArray.single().jsonObject["cache_hit"])
        assertFalse("Assembly time is not a fresh source observation", "observed_at_ms" in live)
        assertTrue("The report still records its assembly time", "assembled_at_ms" in live)
        assertEquals("main", fixture.session.context()?.ref)
        assertEquals(fixture.commitSha, fixture.session.snapshot()?.commitSha)
        assertEquals("/repos/owner/repo/commits/${fixture.commitSha}", fixture.requests[3])
        val receipt = accounting.finish(AgentToolResult("live", ToolResultContent.Json(live), false), false).executionReceipt!!
        assertEquals(JsonPrimitive(3), receipt["read_requests"])
        assertEquals(JsonPrimitive(3), receipt["read_response_observations"])
        assertEquals(JsonPrimitive(0), receipt["cached_read_observations"])
        assertEquals(JsonPrimitive(true), receipt["live_read_performed"])
        assertEquals(JsonPrimitive("PERFORMED"), receipt["live_read_status"])
    }

    @Test fun forcedReadAndContentSearchRevisitWarmSourceWithoutChangingPinnedRevision() = runTest {
        for (action in listOf("read_ranges", "search_repository")) {
            val fixture = Fixture()
            val args = if (action == "read_ranges") {
                buildJsonObject { put("path", ".github/workflows/build.yml") }
            } else {
                buildJsonObject {
                    put("query", "bounded fixture match")
                    put("mode", "literal")
                }
            }
            fixture.execute(action, args)
            val initial = fixture.requests.size
            fixture.execute(action, args)
            assertEquals("Ordinary immutable reads still use their caches", initial, fixture.requests.size)
            val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            val live = withContext(accounting) { fixture.execute(action, args) }
            assertEquals(action, initial + 3, fixture.requests.size)
            assertTrue("Known source content must be returned for $action", live.getValue("items").jsonArray.isNotEmpty())
            assertEquals(fixture.commitSha, fixture.session.snapshot()?.commitSha)
            val receipt = accounting.finish(AgentToolResult(action, ToolResultContent.Json(live), false), false).executionReceipt!!
            assertEquals(JsonPrimitive(true), receipt["live_read_performed"])
            assertEquals(JsonPrimitive(0), receipt["cached_read_observations"])
        }
    }

    @Test fun forcedVerificationDoesNotFallBackToOldSuccessAfterTransportFailure() = runTest {
        val fixture = Fixture()
        fixture.execute("workflow_catalog")
        fixture.failBlobRead = true
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
        val failed = withContext(accounting) { fixture.execute("workflow_catalog") }
        assertEquals(JsonPrimitive(false), failed["page_complete"])
        assertEquals(JsonPrimitive(false), failed.getValue("workflows").jsonArray.single().jsonObject.getValue("definition").jsonObject["metadata_available"])
        val receipt = accounting.finish(AgentToolResult("failed-live", ToolResultContent.Json(failed), false), false).executionReceipt!!
        assertEquals(JsonPrimitive(false), receipt["live_read_performed"])
        assertEquals(JsonPrimitive("RESPONSE_NOT_FULLY_OBSERVED"), receipt["live_read_status"])
        assertEquals(JsonPrimitive(1), receipt["unobserved_read_requests"])
    }

    @Test fun aLocalOnlyOperationExplicitlyReportsThatForcedTransportWasNotPerformed() {
        val receipt = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            .finish(AgentToolResult("local", ToolResultContent.Text("Retained local state"), false), false).executionReceipt!!
        assertEquals(JsonPrimitive(false), receipt["live_read_performed"])
        assertEquals(JsonPrimitive("NOT_PERFORMED"), receipt["live_read_status"])
        assertEquals(JsonPrimitive("LOCAL_OR_REPLAYED"), receipt["phase"])
    }

    @Test fun limitAndEofReadsPreserveExactContinuationAndRejectConflictingBounds() = runTest {
        val content = (1..2105).joinToString("\n") { "line $it" }
        val fixture = Fixture(content)
        fun args(start: Int, key: String, value: Int) = buildJsonObject {
            put("path", ".github/workflows/build.yml")
            put("start_line", start)
            put(key, value)
        }
        val first = fixture.execute("read_ranges", args(1, "max_lines", 50))["items"]!!.jsonArray.single().jsonObject
        assertEquals((1..50).joinToString("\n") { "line $it" }, first["content"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(51), first["continuation"]!!.jsonObject["start_line"])
        assertEquals(JsonPrimitive(50), first["continuation"]!!.jsonObject["max_lines"])
        val eof = fixture.execute("read_ranges", args(1, "end_line", -1))["items"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(2000), eof["end_line"])
        assertEquals(JsonPrimitive(false), eof["eof"])
        val tail = fixture.execute("read_ranges", eof["continuation"]!!.jsonObject)["items"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(true), tail["eof"])
        assertEquals((2001..2105).joinToString("\n") { "line $it" }, tail["content"]!!.jsonPrimitive.content)
        assertEquals(content, eof["content"]!!.jsonPrimitive.content + "\n" + tail["content"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.execute("read_ranges", JsonObject(args(1, "end_line", -1) + ("max_lines" to JsonPrimitive(10))))
            }
        }
    }

    private class Fixture(private val content: String = "name: bounded fixture match\non: [push]\njobs: {}\n") {
        val commitSha = "a".repeat(40)
        private val treeSha = "b".repeat(40)
        private val blobSha = "c".repeat(40)
        val requests = mutableListOf<String>()
        var failBlobRead = false
        val session = GitHubRepositorySession(
            "account",
            "connection",
            "conversation",
            "task",
            GitHubRepositoryStateStore(),
            request = { path ->
                requests += path
                val requestId = GitHubDispatchAccounting.started(false)
                if (failBlobRead && "/git/blobs/" in path) throw java.io.IOException("Fixture disconnect")
                val response = when {
                    "/commits/" in path -> buildJsonObject {
                        put("sha", commitSha)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", treeSha) }) })
                    }
                    "/git/trees/" in path -> buildJsonObject {
                        put("truncated", false)
                        put(
                            "tree",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("path", ".github/workflows/build.yml")
                                        put("sha", blobSha)
                                        put("type", "blob")
                                        put("size", content.encodeToByteArray().size)
                                    }
                                )
                            )
                        )
                    }
                    "/git/blobs/" in path -> buildJsonObject {
                        put("sha", blobSha)
                        put("encoding", "base64")
                        put("content", Base64.getEncoder().encodeToString(content.encodeToByteArray()))
                    }
                    else -> error("Unexpected request $path")
                }
                GitHubDispatchAccounting.responded(false, 200, requestId)
                response
            },
            commit = { _, _ -> error("No mutation is authorized by this fixture") }
        )

        suspend fun execute(action: String, values: JsonObject = JsonObject(emptyMap())): JsonObject =
            session.execute(action, "owner", "repo", JsonObject(mapOf<String, JsonElement>("ref" to JsonPrimitive("main")) + values)).jsonObject
    }
}
