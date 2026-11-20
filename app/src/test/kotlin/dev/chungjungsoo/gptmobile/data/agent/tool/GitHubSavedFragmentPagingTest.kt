package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedFragmentPagingTest {
    @Test fun sourceReadsDoNotSpendDozensOfModelRoundsOnTinyStringFragments() {
        val source = "val name = \"retained source\";\n".repeat(1600)
        val original = buildJsonObject { put("content", source) }
        for ((pageBytes, maximumPages) in listOf(8192 to 9, 4096 to 23)) {
            val views = GitHubResultViews(pageBytes = pageBytes)
            var result = views.present("scope", "github_read", "github_read", AgentToolResult("source", ToolResultContent.Json(original), false))
            val recovered = StringBuilder()
            var expectedOffset = 0
            var pages = 0
            while (true) {
                val page = data(result)
                assertFalse(result.isError)
                assertTrue(page.toString().encodeToByteArray().size <= pageBytes)
                assertEquals(expectedOffset, page.getValue("fragment_offset").jsonPrimitive.int)
                assertEquals(GitHubRepositoryStateStore.digest(original.toString()), page.getValue("source_sha256").jsonPrimitive.content)
                appendContent(page, recovered)
                expectedOffset = page.getValue("fragment_end").jsonPrimitive.int
                assertTrue("Saved source must fit in $maximumPages pages at $pageBytes bytes", ++pages <= maximumPages)
                if (page.getValue("local_page_complete").jsonPrimitive.boolean) {
                    assertEquals(page.getValue("total_fragments").jsonPrimitive.int, expectedOffset)
                    break
                }
                result = views.read("scope", "github_read", "github_read", cursor(page, "continuation_call"), "page-$pages")
                assertFalse(result.dispatched)
            }
            assertEquals(source, recovered.toString())
        }
    }

    @Test fun smallerIssuedRecoveryAfterRestoreKeepsEveryUnicodeCharacterAndExactCursorBoundary() {
        val source = "quoted \"line\"\n🍁漢字\t".repeat(1200)
        val views = GitHubResultViews()
        val first = views.present(
            "scope",
            "github_read",
            "github_read",
            AgentToolResult("source", ToolResultContent.Json(buildJsonObject { put("content", source) }), false)
        )
        val firstPage = data(first)
        val recovery = cursor(firstPage, "recovery_call")
        val restored = GitHubResultViews()
        val snapshot = requireNotNull(views.checkpoint("scope", first)).second
        assertTrue(restored.restore("scope", "github_read", "github_read", recovery, snapshot))
        val recovered = StringBuilder()
        var result = restored.read("scope", "github_read", "github_read", recovery, "restore")
        var pages = 0
        while (true) {
            val page = data(result)
            assertFalse(result.isError)
            assertFalse(result.dispatched)
            assertTrue(page.toString().encodeToByteArray().size <= 1536)
            appendContent(page, recovered)
            assertTrue(++pages < 150)
            if (page.getValue("local_page_complete").jsonPrimitive.boolean) break
            result = restored.read("scope", "github_read", "github_read", cursor(page, "continuation_call"), "page-$pages")
        }
        assertEquals(source, recovered.toString())
    }

    @Test fun packingNeverJoinsDifferentPointersOverlapsOrTypedScalars() {
        fun fragment(pointer: String, offset: Int, value: JsonPrimitive, total: Int = 6) = buildJsonObject {
            put("pointer", pointer)
            put("offset", offset)
            put("total_characters", total)
            put("value", value)
        }
        val first = fragment("/content", 0, JsonPrimitive("abc"))
        assertNull(GitHubSavedFragments.combine(first, fragment("/other", 3, JsonPrimitive("def"))))
        assertNull(GitHubSavedFragments.combine(first, fragment("/content", 2, JsonPrimitive("def"))))
        assertNull(GitHubSavedFragments.combine(first, fragment("/content", 3, JsonPrimitive(123))))
        assertNull(GitHubSavedFragments.combine(first, fragment("/content", 3, JsonPrimitive("def"), 7)))
        assertNull(GitHubSavedFragments.combine(first, fragment("/content", 3, JsonPrimitive("defg"))))
    }

    private fun appendContent(page: JsonObject, target: StringBuilder) {
        for (value in page.getValue("fragments") as JsonArray) {
            val fragment = value.jsonObject
            assertEquals("/content", fragment.getValue("pointer").jsonPrimitive.content)
            assertEquals(target.length, fragment.getValue("offset").jsonPrimitive.int)
            target.append(fragment.getValue("value").jsonPrimitive.content)
        }
    }

    private fun data(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(page: JsonObject, name: String) = page.getValue(name).jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
}
