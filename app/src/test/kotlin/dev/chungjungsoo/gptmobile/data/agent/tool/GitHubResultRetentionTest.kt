package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.github.GitHubSavedViewStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

class GitHubResultRetentionTest {
    private fun body(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(result: AgentToolResult) = body(result).getValue("continuation_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content

    @Test fun expandedFragmentCapacityUsesExactCompressedPagesAcrossRestart() = runBlocking {
        val directory = Files.createTempDirectory("github-compressed-pages").toFile()
        try {
            val source = buildJsonObject {
                put("id", 81)
                put("body", "quote \" \\ \n🍁".repeat(2000))
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "list_releases")
                        put("page", 2)
                    }
                )
            }
            var views = GitHubResultViews(pageBytes = 2048, capacityBytes = 32 * 1024)
            val store = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "account:conversation")
            var shown = views.present("scope", "github_release", "github_release", AgentToolResult("read", ToolResultContent.Json(source), false), buildJsonObject { put("action", "list") })
            assertFalse(shown.isError)
            assertEquals(JsonPrimitive("GZIP_JSON_UTF8"), shown.executionReceipt?.get("saved_result_encoding"))
            val recovered = StringBuilder()
            var pages = 0
            while (true) {
                val page = body(shown)
                assertTrue(page.toString().encodeToByteArray().size <= 2048)
                page.getValue("fragments").jsonArray.forEach { raw ->
                    val chunk = raw.jsonObject
                    assertEquals(JsonPrimitive("json_text"), chunk["kind"])
                    assertEquals(recovered.length, chunk.getValue("offset").jsonPrimitive.int)
                    val text = chunk.getValue("value").jsonPrimitive.content
                    assertEquals(JsonPrimitive(GitHubRepositoryStateStore.digest(text)), chunk["sha256"])
                    recovered.append(text)
                }
                if (page["local_page_complete"] == JsonPrimitive(true)) {
                    assertEquals(JsonPrimitive(2), page.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject["page"])
                    assertEquals(JsonPrimitive(GitHubRepositoryStateStore.digest(recovered.toString())), page["source_sha256"])
                    break
                }
                val next = cursor(shown)
                if (pages++ == 2) {
                    val (id, snapshot) = requireNotNull(views.checkpoint("scope", shown))
                    store.save(id, snapshot)
                    val restarted = GitHubSavedViewStore(GitHubRepositoryStateStore(directory), "account:conversation")
                    views = GitHubResultViews(pageBytes = 2048, capacityBytes = 32 * 1024)
                    assertTrue(views.restore("scope", "github_release", "github_release", next, requireNotNull(restarted.load(id))))
                }
                shown = views.read("scope", "github_release", "github_release", next, "local-$pages")
                assertFalse(shown.dispatched)
                assertFalse(shown.isError)
                assertTrue(pages < 1000)
            }
            assertEquals(source, Json.parseToJsonElement(recovered.toString()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun responseAboveOneMiBRetainsAnExactRecoverableSourceWithoutRaisingTheCacheCap() {
        val views = GitHubResultViews()
        val source = buildJsonObject {
            put("id", 42)
            put("body", "release notes ".repeat(100_000))
        }
        val original = AgentToolResult("release", ToolResultContent.Json(source), false)
        val shown = views.present("scope", "github_release", "github_release", original, buildJsonObject { put("action", "detail") })
        assertFalse(shown.isError)
        assertEquals(original.content, shown.retainedContent)
        assertEquals(JsonPrimitive(true), shown.executionReceipt?.get("retained_original_complete"))
        val (_, checkpoint) = requireNotNull(views.checkpoint("scope", shown))
        assertTrue(checkpoint.toString().encodeToByteArray().size < 64 * 1024)
        val next = cursor(shown)
        assertTrue(GitHubResultViews().restore("scope", "github_release", "github_release", next, checkpoint))
        assertFalse(GitHubResultViews().restore("scope", "github_release", "github_release", next, JsonObject(checkpoint + ("source_sha256" to JsonPrimitive("0".repeat(64))))))
    }

    @Test fun presentationExhaustionNeverReclassifiesAnAcceptedWriteOrDropsItsOriginal() {
        val views = GitHubResultViews(capacityBytes = 100)
        val receipt = buildJsonObject { put("accepted_mutations", 1) }
        val original = AgentToolResult(
            "write",
            ToolResultContent.Json(
                buildJsonObject {
                    put("commit_sha", "a".repeat(40))
                    put("body", "receipt".repeat(3000))
                }
            ),
            false,
            executionReceipt = receipt
        )
        val shown = views.present("scope", "github_commit", "github_commit", original)
        assertFalse(shown.isError)
        assertEquals(null, shown.errorCode)
        assertEquals(original.content, shown.retainedContent)
        assertEquals(JsonPrimitive(1), shown.executionReceipt?.get("accepted_mutations"))
        assertEquals(JsonPrimitive("RETAINED_RESULT_CAPACITY"), shown.executionReceipt?.get("presentation_error_code"))
        assertEquals(JsonPrimitive("UNAVAILABLE"), shown.executionReceipt?.get("saved_view_persistence"))
    }

    @Test fun releaseListKeepsRemotePaginationAndOriginalNotesWhileShowingBoundedPreviews() = runBlocking {
        var calls = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "Backend", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                val page = arguments["page"]?.jsonPrimitive?.int ?: 1
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("page", page)
                            put("per_page", 1)
                            put("has_more", page == 1)
                            put(
                                "items",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("id", page)
                                            put("tag_name", "v$page")
                                            put("body", "retained release notes ".repeat(10_000))
                                        }
                                    )
                                )
                            )
                        }
                    ),
                    false
                )
            }
        }
        val store = GitHubSavedViewStore(GitHubRepositoryStateStore(), "saved-original")
        fun reopened() = GitHubOperationTool("github_release", backend, "scope", views = GitHubResultViews(), loadSavedView = store::load, saveSavedView = store::save)
        val tool = reopened()
        val first = tool.execute(
            "page-1",
            buildJsonObject {
                put("action", "list")
                put("per_page", 1)
            }
        )
        assertFalse(first.isError)
        assertTrue(first.content.toString().encodeToByteArray().size < 8 * 1024)
        val original = (first.retainedContent as ToolResultContent.Json).value.jsonObject
        assertTrue(original.getValue("items").jsonArray.single().jsonObject.getValue("body").jsonPrimitive.content.length > 100_000)
        val remote = body(first).getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive(2), remote["page"])
        val recovered = StringBuilder()
        val reader = reopened()
        var originalPage = reader.execute("saved-original", body(first).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject)
        var pages = 0
        while (recovered.length <= 512) {
            assertFalse(originalPage.isError)
            assertFalse(originalPage.dispatched)
            body(originalPage).getValue("fragments").jsonArray.map { it.jsonObject }.filter { it["pointer"] == JsonPrimitive("/items/0/body") }.forEach {
                assertEquals(recovered.length, it.getValue("offset").jsonPrimitive.int)
                recovered.append(it.getValue("value").jsonPrimitive.content)
            }
            if (recovered.length <= 512) originalPage = reader.execute("saved-next", buildJsonObject { put("view_cursor", cursor(originalPage)) })
            assertTrue(++pages < 20)
        }
        assertEquals(original.getValue("items").jsonArray.single().jsonObject.getValue("body").jsonPrimitive.content.take(recovered.length), recovered.toString())
        assertEquals("Recovery uses the captured original, without a remote refresh", 1, calls)
        val second = reader.execute("page-2", remote)
        assertFalse(second.isError)
        assertEquals(2, calls)
        assertEquals(JsonPrimitive(false), body(second).getValue("data").jsonObject["has_more"])
    }
}
