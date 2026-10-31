package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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

class GitHubResultViewsStressTest {
    private fun data(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(page: JsonObject) = page["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject["view_cursor"]!!.jsonPrimitive.content

    @Test fun everySavedErrorPageKeepsItsFailureClassificationWithoutRedispatch() {
        val views = GitHubResultViews(pageBytes = 2048)
        val raw = AgentToolResult("first", ToolResultContent.Json(JsonPrimitive("failure ".repeat(1000))), true, errorCode = "GITHUB_WRITE_BLOCKED")
        var current = views.present("scope", "github_read", "github_read", raw)
        var count = 0
        while (true) {
            assertTrue(current.isError)
            assertEquals("GITHUB_WRITE_BLOCKED", current.errorCode)
            if (count > 0) assertFalse(current.dispatched)
            val page = data(current)
            assertTrue(page.toString().toByteArray().size <= 2048)
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            current = views.read("scope", "github_read", "github_read", cursor(page), "next")
            assertTrue(++count < 100)
        }
        assertTrue(count > 1)
    }

    @Test fun multibyteTextReconstructsExactlyAcrossEveryUtf16Offset() {
        val views = GitHubResultViews(pageBytes = 2048)
        val source = "quoted \"line\"\n🍁漢字".repeat(200)
        val raw = AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("text", source) }), false)
        var page = data(views.present("scope", "github_read", "github_read", raw))
        val recovered = StringBuilder()
        var count = 0
        while (true) {
            assertTrue(page.toString().toByteArray().size <= 2048)
            for (fragment in page["fragments"]!!.jsonArray) {
                val row = fragment.jsonObject
                assertEquals("/text", row["pointer"]!!.jsonPrimitive.content)
                assertEquals(recovered.length, row["offset"]!!.jsonPrimitive.int)
                recovered.append(row["value"]!!.jsonPrimitive.content)
            }
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            page = data(views.read("scope", "github_read", "github_read", cursor(page), "next"))
            assertTrue(++count < 100)
        }
        assertEquals(source, recovered.toString())
    }

    @Test fun largeNumericScalarKeepsItsJsonTypeWhenReplayingASavedView() {
        val views = GitHubResultViews(pageBytes = 2048)
        val number = Json.parseToJsonElement("1".repeat(200))
        val raw = AgentToolResult("first", ToolResultContent.Json(number), false)
        val first = data(views.present("scope", "github_read", "github_read", raw))
        val recovery = first["recovery_call"]!!.jsonObject["arguments"]!!.jsonObject["view_cursor"]!!.jsonPrimitive.content
        val replay = data(views.read("scope", "github_read", "github_read", recovery, "replay"))
        val recovered = replay["fragments"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive
        assertFalse(recovered.isString)
        assertEquals(number, recovered)
    }

    @Test fun continuationPointersEscapeSlashAndTildeLikeDataPointers() {
        val views = GitHubResultViews()
        val raw = AgentToolResult(
            "first",
            ToolResultContent.Json(
                buildJsonObject {
                    put(
                        "/~_continuation",
                        buildJsonObject {
                            put("action", "list_commits")
                            put("page", 2)
                        }
                    )
                }
            ),
            false
        )
        val page = data(views.present("scope", "github_context", "github_context", raw))
        assertEquals("/~1~0_continuation", page["remote_continuations"]!!.jsonArray.single().jsonObject["pointer"]!!.jsonPrimitive.content)
    }

    @Test fun capacityRecoveryNarrowsTheSameImmutableFileOffsetAndDoesNotLoop() {
        val views = GitHubResultViews(capacityBytes = 100)
        val raw = AgentToolResult(
            "first",
            ToolResultContent.Json(
                buildJsonObject {
                    put("sha", "a".repeat(40))
                    put("data", "x".repeat(4000))
                }
            ),
            false
        )
        val request = buildJsonObject {
            put("action", "commit")
            put("page", 2)
            put("per_page", 30)
        }
        val first = data(views.present("scope", "github_context", "github_context", raw, request))
        val call = first["recovery_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals(JsonPrimitive(31), call["page"])
        assertEquals(JsonPrimitive(1), call["per_page"])
        assertEquals(JsonPrimitive(false), call["include_patches"])
        assertEquals(JsonPrimitive("a".repeat(40)), call["commit_sha"])
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(call, GitHubOperationTool.definition("github_context").inputSchema))
        val stillTooLarge = data(views.present("scope", "github_context", "github_context", raw, call))
        assertFalse("recovery_call" in stillTooLarge)
    }
}
