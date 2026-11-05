package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportDeduplicatorTest {
    @Test fun repeatedCriticalCaptureUsesReferencesAndPreservesEveryDistinctStackFrame() {
        val writer = DiagnosticExportDeduplicator()
        val rows = listOf("event body", "at first()", "at second()")
        rows.forEachIndexed { index, row -> assertFalse(writer.accept(row, "event:$index", "critical.log", index).duplicate) }
        rows.forEachIndexed { index, row -> assertTrue(writer.accept(row, "event:$index", "current.log", index + 100).duplicate) }
        val batch = Json.parseToJsonElement(writer.flush()!!).jsonObject
        assertEquals("3", batch.getValue("referenceCount").jsonPrimitive.content)
        assertEquals("false", batch.getValue("countAsIssue").jsonPrimitive.content)
        assertEquals(listOf("event:0", "event:1", "event:2"), batch.getValue("references").jsonArray.map { it.jsonObject.getValue("eventId").jsonPrimitive.content })
        assertEquals("critical.log", batch.getValue("references").jsonArray.first().jsonObject.getValue("canonicalSource").jsonPrimitive.content)
        assertNull(writer.flush())
    }

    @Test fun reusedIdentityWithDifferentTextAndDifferentSequenceNeverLosesEvidence() {
        val writer = DiagnosticExportDeduplicator()
        assertFalse(writer.accept("at same()", "e:1", "critical.log", 1).duplicate)
        assertFalse(writer.accept("at changed()", "e:1", "current.log", 1).duplicate)
        assertFalse(writer.accept("at same()", "e:2", "current.log", 2).duplicate)
        assertNull(writer.flush())
    }

    @Test fun boundedBatchAndEvictionRetainFullEvidenceInsteadOfGuessingDuplicates() {
        val writer = DiagnosticExportDeduplicator(limit = 2)
        writer.accept("first", "a", "first.log", 0)
        var batches = 0
        repeat(65) { if (writer.accept("first", "a", "second.log", it).batch != null) batches++ }
        assertEquals(2, batches)
        assertEquals("1", Json.parseToJsonElement(writer.flush()!!).jsonObject.getValue("referenceCount").jsonPrimitive.content)
        writer.accept("second", "b", "first.log", 1)
        writer.accept("third", "c", "first.log", 2)
        assertFalse(writer.accept("first", "a", "third.log", 3).duplicate)
    }
}
