package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedPassageDecoderArrayTest {
    private val sources = listOf(CombinedModelResponse("alpha", "Alpha", content = "A source claim about history."))

    private fun passage(text: String) = buildJsonObject {
        put("type", "passage")
        put("text", text)
        put("profiles", JsonArray(listOf(JsonPrimitive("C1"))))
        put("claims", JsonArray(listOf(JsonPrimitive("C1.1"))))
        put("sources", JsonArray(emptyList()))
    }.toString()

    @Test fun fencedArraysHandleEveryChunkBoundaryAndEscapedBraces() {
        val expected = "A quoted \"finding\" with {braces}, [brackets] and a \\ slash."
        val payload = "```json\n[\n${passage(expected)}\n]\n```"
        for (size in 1..payload.length) {
            val decoder = CombinedPassageDecoder(listOf(sources.single().copy(content = expected)), CombinedEvidenceLedger(), 10000)
            val deltas = payload.chunked(size).flatMap { decoder.accept(it) } + decoder.accept("", true)
            assertEquals("Chunk size $size", expected, deltas.joinToString(""))
            assertTrue(decoder.issues.isEmpty())
            assertEquals(setOf("alpha"), decoder.passages.single().profileUids)
        }
    }

    @Test fun markdownLinksAndCitationsRetainPlainTextFallback() {
        for (text in listOf("[Reference](https://example.org) introduces the topic.", "[1] A cited historical fact.")) {
            for (size in 1..text.length) {
                val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
                val deltas = text.chunked(size).flatMap { decoder.accept(it) } + decoder.accept("", true)
                assertEquals(text, deltas.joinToString(""))
            }
        }
    }

    @Test fun truncatedArrayRetainsCompletePassageAndReportsMalformedTail() {
        val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
        decoder.accept("[${passage("A complete historical finding.")}, {\"type\":", true)
        assertEquals("A complete historical finding.", decoder.answer())
        assertTrue("INVALID_DELIVERY_RECORD" in decoder.issues)
    }

    @Test fun recordsAfterArrayClosureAreRejected() {
        val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
        decoder.accept("[${passage("The actual answer.")}]${passage("Unexpected trailing record.")}", true)
        assertEquals("The actual answer.", decoder.answer())
        assertTrue("INVALID_DELIVERY_RECORD" in decoder.issues)
    }
}
