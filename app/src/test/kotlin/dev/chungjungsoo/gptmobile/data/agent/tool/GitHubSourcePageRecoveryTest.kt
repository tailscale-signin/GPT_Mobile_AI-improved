package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
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

class GitHubSourcePageRecoveryTest {
    @Test fun smallRecoveryCanGrowAfterRestartAndReconstructAllThreeFilesExactly() {
        val originals = listOf(88, 32, 82).mapIndexed { file, lines ->
            "src/File$file.kt" to (1..lines).joinToString("\n", postfix = "\n") { line -> "val line$line = \"captured source 🍁 for file $file and unchanged revision\"" }
        }.toMap()
        val source = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put(
                "items",
                JsonArray(
                    originals.map { (path, content) ->
                        buildJsonObject {
                            put("path", path)
                            put("sha", "b".repeat(40))
                            put("start_line", 1)
                            put("content", content)
                        }
                    }
                )
            )
        }
        val name = "github_read__connection"
        var views = GitHubResultViews()
        val shown = views.present("scope", "github_read", name, AgentToolResult("original", ToolResultContent.Json(source), false))
        val first = data(shown)
        assertEquals(JsonPrimitive(true), first["source_page"])
        val offered = first.getValue("recovery_options").jsonArray.map { cursor(it.jsonObject).substringAfterLast(':').toInt() }.toSet()
        assertEquals(setOf(4096, 8192), offered)
        var result = views.read("scope", "github_read", name, cursor(first.getValue("recovery_call").jsonObject), "small")
        val collected = originals.keys.associateWith { StringBuilder() }
        var pages = 0
        while (true) {
            assertFalse(result.isError)
            val page = data(result)
            val current = cursor(page.getValue("recovery_call").jsonObject)
            val allowance = if (pages == 0) 1536 else 8192
            assertTrue(page.toString().encodeToByteArray().size <= allowance)
            for (item in page.getValue("items").jsonArray) {
                val row = item.jsonObject
                val content = collected.getValue(row.getValue("path").jsonPrimitive.content)
                assertEquals(content.length, row.getValue("range_offset").jsonPrimitive.int)
                content.append(row.getValue("content").jsonPrimitive.content)
            }
            pages++
            if (page["local_page_complete"] == JsonPrimitive(true)) break
            val choices = page["continuation_options"]?.jsonArray.orEmpty().map { it.jsonObject } + page.getValue("continuation_call").jsonObject
            val next = choices.maxBy { cursor(it).substringAfterLast(':').toInt() }
            val nextCursor = cursor(next)
            assertEquals(8192, nextCursor.substringAfterLast(':').toInt())
            val snapshot = requireNotNull(views.checkpoint("scope", result)).second
            val restored = GitHubResultViews()
            assertFalse(restored.restore("other", "github_read", name, nextCursor, snapshot))
            assertTrue(restored.restore("scope", "github_read", name, nextCursor, snapshot))
            val guessed = current.substringBeforeLast(':').substringBeforeLast(':') + ":${current.split(':')[1].toInt() + 1}:8192"
            assertTrue(restored.read("scope", "github_read", name, guessed, "guessed").isError)
            views = restored
            result = views.read("scope", "github_read", name, nextCursor, "next-$pages")
            assertTrue("Adaptive source traversal must remain bounded", pages < 12)
        }
        assertEquals(originals, collected.mapValues { it.value.toString() })
        assertTrue(pages < 12)
    }

    private fun data(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(recipe: JsonObject) = recipe.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
}
