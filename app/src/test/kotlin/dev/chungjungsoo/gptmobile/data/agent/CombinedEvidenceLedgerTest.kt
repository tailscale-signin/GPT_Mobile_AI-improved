package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedEvidenceLedgerTest {
    private fun event(id: String, run: String, tool: String, result: String, error: Boolean = false) = ToolEvent(id, run, 1, id, null, null, tool, tool, "{}", result, "JSON", if (error) "FAILED" else "COMPLETED", error)

    @Test fun canonicalArticleRetainsAllOwnersEventsAndReadQuality() {
        val url = "https://example.org/news/article"
        val search = event("s", "a", "web_search", """{"sources":[{"url":"$url?utm_source=search","title":"A specific article","publishedAt":"2026-10-16"}]}""")
        val passage = "Observed article text with sufficient original context, attributed numbers and explicit limitations. ".repeat(2)
        val read = event(
            "r",
            "b",
            "read_url",
            buildJsonObject {
                put("url", url)
                put("content", passage)
            }.toString()
        )
        val ledger = CombinedEvidenceLedger.freeze(null, listOf(search, read), mapOf("a" to "alpha", "b" to "beta"))
        val source = ledger.records.single()
        assertEquals(url, source.url)
        assertTrue(source.readable)
        assertTrue(source.article)
        assertEquals("2026-10-16", source.publishedAt)
        assertEquals(setOf("alpha", "beta"), source.profileUids)
        assertEquals(setOf("s", "r"), source.toolEventIds)
        val decoder = CombinedPassageDecoder(listOf(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("alpha", "Alpha", content = passage)), ledger, 10000)
        val record = """{"type":"passage","text":"A rewritten claim.","profiles":["C1"],"claims":["C1.1"],"sources":["${source.id}"]}"""
        decoder.accept(record, true)
        assertTrue(decoder.answer().contains(source.url))
        assertEquals("CITED_UNVERIFIED", decoder.passages.single().evidenceStatus)
    }

    @Test fun homepageAndSearchSnippetNeverQualifyAsReadableNews() {
        val payload = """{"url":"https://example.org/","title":"News homepage","content":"${"snippet ".repeat(40)}","publishedAt":"2026-10-16"}"""
        val ledger = CombinedEvidenceLedger.freeze(null, listOf(event("s", "a", "web_search", payload)), mapOf("a" to "alpha"))
        assertFalse(ledger.records.single().readable)
        assertFalse(ledger.records.single().article)
    }

    @Test fun failedToolsAndSuccessfulNativeDateRemainDistinctFromModelClaims() {
        val date = event("date", "a", "current_date", """{"date":"2026-10-16","time":"18:00:00","zone":"America/Toronto"}""")
        val ledger = CombinedEvidenceLedger.freeze(null, listOf(date, event("bad", "a", "perplexity_search", "HTTP 401", true)), mapOf("a" to "alpha"))
        assertTrue(ledger.records.isEmpty())
        assertEquals("2026-10-16", ledger.trustedDateObservation["date"])
        assertEquals("America/Toronto", ledger.trustedDateObservation["zone"])
        assertEquals(1, ledger.failedTools.size)
    }
}
