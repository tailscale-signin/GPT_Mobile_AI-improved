package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRecordProtocolTest {
    @Test fun fencedJsonRecordsSurviveEveryTransportBoundaryAndCheckpointRestore() {
        val wire = "```jsonl\n" + passage(1, "Exact quoted text with { braces } and a backslash \\") + "\n" +
            """{"type":"end","seq":2,"id":"END","lastSeq":1}""" + "\n```"
        for (cut in 0..wire.length) {
            val first = protocol()
            first.accept(wire.take(cut))
            val restored = protocol(first.checkpoint())
            restored.accept(wire.drop(cut))
            assertNull("boundary $cut", restored.checkpoint().failure)
            assertTrue("boundary $cut", restored.checkpoint().ended)
            assertTrue("boundary $cut", restored.checkpoint().pending.isBlank())
        }
    }

    @Test fun proseOutsideRecordsIsStillRejected() {
        val parser = protocol()
        parser.accept("Here is the answer:\n" + passage(1, "Text"))
        assertEquals("INVALID_DELIVERY_RECORD", parser.checkpoint().failure)
        assertTrue(parser.checkpoint().records.isEmpty())
    }

    private fun protocol(state: CombinedRecordCheckpoint = CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision")) =
        CombinedRecordProtocol(state, setOf("C1.1", "C1.2"), setOf("C1"), setOf("E1"), 100_000)

    private fun passage(sequence: Int, text: String, id: String = "P$sequence") = buildJsonObject {
        put("type", "passage")
        put("seq", sequence)
        put("id", id)
        put("text", text)
        put("claims", JsonArray(listOf(JsonPrimitive("C1.1"))))
        put("contributors", JsonArray(listOf(JsonPrimitive("C1"))))
        put("evidence", JsonArray(emptyList()))
    }.toString()

    @Test fun interruptedPortugalParagraphRestartsAtTheSameSequenceAfterSerialization() {
        val first = passage(1, "The Estado Novo preceded the democratic transition.")
        val middle = passage(2, "The colonial wars and the 1974 Carnation Revolution changed Portugal.")
        val cut = middle.indexOf("Carnation") + 4
        val parser = protocol()
        parser.accept(first + "\n" + middle.take(cut))
        val saved = Json.decodeFromString<CombinedRecordCheckpoint>(Json.encodeToString(parser.checkpoint()))
        assertEquals(1, saved.lastSequence)
        assertEquals(middle.take(cut), saved.pending)
        val resumed = protocol(saved)
        val context = resumed.continuationContext()
        assertTrue(context.contains("next_sequence\":2"))
        assertTrue(context.contains("1974"))
        resumed.accept(middle + "\n" + passage(3, "Portugal joined the European Communities in 1986."))
        resumed.accept("{\"type\":\"end\",\"seq\":4,\"id\":\"END\",\"lastSeq\":3}")
        assertEquals(listOf("P1", "P2", "P3", "END"), resumed.checkpoint().records.map { it.id })
        assertTrue(resumed.checkpoint().ended)
        assertNull(resumed.checkpoint().failure)
        assertEquals(saved.pending, resumed.checkpoint().recoveredFragments.single().raw)
    }

    @Test fun everyTransportBoundaryPreservesEscapesUnicodeAndExactRecordTextAcrossRestart() {
        val wire = passage(1, "  Quoted \"text\", backslash \\, newline\n and 🦋.  ")
        for (cut in 0..wire.length) {
            val initial = protocol()
            initial.accept(wire.take(cut))
            val saved = Json.decodeFromString<CombinedRecordCheckpoint>(Json.encodeToString(initial.checkpoint()))
            val resumed = protocol(saved)
            resumed.accept(wire.drop(cut))
            assertNull("boundary $cut", resumed.checkpoint().failure)
            assertEquals(protocol().also { it.accept(wire) }.checkpoint().records, resumed.checkpoint().records)
        }
    }

    @Test fun identicalReplayIsIgnoredButAChangedIdOrSequenceCannotRewriteCommittedContent() {
        val parser = protocol()
        val first = passage(1, "Preserve this paragraph.")
        assertEquals(1, parser.accept(first).size)
        assertTrue(parser.accept(first).isEmpty())
        val before = parser.checkpoint().records
        parser.accept(passage(1, "Changed paragraph."))
        assertEquals(before, parser.checkpoint().records)
        assertEquals("CONFLICTING_RECORD_REPLAY", parser.checkpoint().failure)
        assertTrue(parser.checkpoint().pending.contains("Changed paragraph"))
    }

    @Test fun gapsUnknownReferencesAndAnEarlyEndDoNotAdvanceTheCursor() {
        for (invalid in listOf(passage(2, "Skipped first"), passage(1, "Unknown").replace("C1.1", "C9.1"), "{\"type\":\"end\",\"seq\":1,\"id\":\"END\",\"lastSeq\":5}")) {
            val parser = protocol()
            parser.accept(invalid)
            assertEquals(0, parser.checkpoint().lastSequence)
            assertFalse(parser.checkpoint().ended)
            assertTrue(parser.checkpoint().failure != null)
            assertEquals(invalid, parser.checkpoint().pending)
        }
    }

    @Test fun decisionsAndEndShareTheSameContiguousSequenceAsPassages() {
        val parser = protocol()
        parser.accept(passage(1, "A paragraph."))
        parser.accept("""{"type":"decision","seq":2,"id":"D_C1.2","claim":"C1.2","disposition":"OMITTED_SCOPE","reason":"Outside the request."}""")
        parser.accept("""{"type":"end","seq":3,"id":"END","lastSeq":2}""")
        assertTrue(parser.checkpoint().ended)
        parser.accept(passage(4, "Too late."))
        assertEquals("RECORD_AFTER_END", parser.checkpoint().failure)
        assertEquals(3, parser.checkpoint().lastSequence)
    }

    @Test fun changedStoredDigestIsRejectedBeforeResume() {
        val initial = protocol().also { it.accept(passage(1, "Original.")) }.checkpoint()
        val corrupt = initial.copy(records = initial.records.map { it.copy(digest = "corrupt") })
        assertTrue(runCatching { protocol(corrupt) }.isFailure)
    }

    @Test fun anOversizedFragmentIsPreservedWithAnExplicitLimit() {
        val parser = protocol()
        val raw = "{\"type\":\"passage\",\"text\":\"" + "x".repeat(70_000)
        parser.accept(raw)
        assertEquals("COMBINED_FRAGMENT_LIMIT", parser.checkpoint().failure)
        val restored = protocol(Json.decodeFromString(Json.encodeToString(parser.checkpoint())))
        assertEquals(raw, restored.checkpoint().pending)
    }

    @Test fun aQuotedSequenceAndUnpairedSurrogateCannotBecomeCommittedRecords() {
        for (wire in listOf(passage(1, "Text").replace("\"seq\":1", "\"seq\":\"1\""), passage(1, "Text").replace("Text", "\\uD800"))) {
            val parser = protocol()
            parser.accept(wire)
            assertTrue(parser.checkpoint().records.isEmpty())
            assertTrue(parser.checkpoint().failure != null)
        }
    }
}

