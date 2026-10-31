package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiLogPageTest {
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun aLongUnicodeLineReassemblesWithoutLossAcrossPages() {
        val original = "🙂é".repeat(2000)
        val log = GitHubCiLogReader.Log(original, original.encodeToByteArray().size, true)
        var request = args("""{"job_id":8,"start_line":1,"end_line":1}""")
        val pieces = StringBuilder()
        var pages = 0
        while (true) {
            val page = GitHubCiLogPage.render(log, request, byteBudget = 1024)
            pages++
            page["excerpts"]!!.jsonArray.forEach { pieces.append(it.jsonObject["text"]!!.jsonPrimitive.content) }
            assertEquals(JsonPrimitive(false), page["truncated"])
            val next = page["continuation"] as? JsonObject ?: break
            assertEquals(JsonPrimitive(8), next["job_id"])
            request = next
            assertTrue(pages < 30)
        }
        assertTrue(pages > 1)
        assertEquals(original, pieces.toString())
    }

    @Test fun implicitLineWindowsContinueUntilAllCapturedLinesWereRead() {
        val text = (1..401).joinToString("\n") { "Line $it" }
        val log = GitHubCiLogReader.Log(text, text.length, true)
        val first = GitHubCiLogPage.render(log, args("""{"job_id":8}"""))
        assertEquals(200, first["excerpts"]!!.jsonArray.size)
        val second = GitHubCiLogPage.render(log, first["continuation"]!!.jsonObject)
        assertEquals(200, second["excerpts"]!!.jsonArray.size)
        val last = GitHubCiLogPage.render(log, second["continuation"]!!.jsonObject)
        assertEquals(1, last["excerpts"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(false), last["has_more"])
    }

    @Test fun ingestionTruncationAndEmptyRangesAreExplicit() {
        val log = GitHubCiLogReader.Log("captured", 8, false)
        val page = GitHubCiLogPage.render(log, args("""{"job_id":8,"start_line":3}"""))
        assertEquals(JsonPrimitive(true), page["truncated"])
        assertEquals("empty_range", page["status"]!!.jsonPrimitive.content)
        assertTrue(page["limit_note"]!!.jsonPrimitive.content.contains("captured bytes only"))
        assertFalse(page.containsKey("continuation"))
    }
}
