package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrelatedDiagnosticExportTest {
    @Test fun chatExportKeepsCompleteStacksAndOnlyTheirDuplicateReferences() {
        val root = "2026-11-05T02:00:00Z E/Provider: eventId=event-a · runId=run-current-chat · Connection failed"
        val first = "parentEventId=event-a sequence=1 at Network.first()"
        val second = "parentEventId=event-a sequence=2 at Network.second()"
        val other = "2026-11-05T02:00:01Z E/Provider: eventId=event-b · runId=run-other-chat · Another failure"
        val export = export(listOf(root, first, second, other), listOf(root, first, second, other))
        val retained = select(export, listOf("run-current-chat")).map { it.jsonObject }
        val events = retained.filter { it["kind"] != JsonPrimitive("duplicate_reference_batch") }
        assertEquals(setOf("event-a", "event-a:1", "event-a:2"), events.map { it.getValue("eventId").toString().trim('"') }.toSet())
        assertTrue(events.any { it.getValue("message").toString().contains("Network.first") })
        assertTrue(events.any { it.getValue("message").toString().contains("Network.second") })
        val batch = retained.single { it["kind"] == JsonPrimitive("duplicate_reference_batch") }
        assertEquals(JsonPrimitive(3), batch["referenceCount"])
        assertEquals(JsonPrimitive(false), batch["countAsIssue"])
        assertTrue(batch.getValue("references").jsonArray.all { it.jsonObject.getValue("canonicalSource") == JsonPrimitive("critical.log") })
        assertFalse(retained.toString().contains("event-b"))
        assertFalse(retained.toString().contains("run-other-chat"))
    }

    @Test fun matchingContinuationRetainsItsRootAndSiblingFrames() {
        val root = "2026-11-05T02:00:00Z E/Provider: eventId=event-a · Operation failed"
        val matching = "parentEventId=event-a sequence=1 invocationId=invocation-current-chat at First()"
        val sibling = "parentEventId=event-a sequence=2 at Second()"
        val rows = select(export(listOf(root, matching, sibling)), listOf("invocation-current-chat"))
        assertEquals(3, rows.size)
        assertTrue(rows.any { it.jsonObject["kind"] == JsonPrimitive("event") })
        assertTrue(rows.any { it.toString().contains("Second()") })
    }

    @Test fun unknownIdentifiersAndBatchOnlyMatchesDoNotImportAnotherChat() {
        val root = "2026-11-05T02:00:00Z E/Provider: eventId=event-unrelated · runId=run-other-chat · Failure"
        val rows = export(listOf(root), listOf(root))
        assertTrue(select(rows, listOf("unknown-chat-id")).isEmpty())
        assertTrue(select(rows, emptyList()).isEmpty())
        val batchOnly = rows.map { Json.parseToJsonElement(it).jsonObject }.single { it["kind"] == JsonPrimitive("duplicate_reference_batch") }
        assertTrue(select(listOf(batchOnly.toString()), listOf("event-unrelated")).isEmpty())
    }

    private fun select(lines: List<String>, identifiers: Collection<String>) = correlatedDiagnosticRecords({ consume -> consume(lines.asSequence()) }, identifiers)

    private fun export(vararg sources: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val writer = DiagnosticExportDeduplicator()
        val output = mutableListOf<String>()
        sources.forEachIndexed { sourceIndex, lines ->
            val source = if (sourceIndex == 0) "critical.log" else "current.log"
            val cursor = DiagnosticCaptureCursor(source, seen)
            lines.forEachIndexed { index, line ->
                val record = cursor.accept(line, index)
                val decision = writer.accept(line, record.identity, source, index)
                decision.batch?.let(output::add)
                if (!decision.duplicate) output += structuredDiagnosticLine(line, source, record.duplicate, record.parent, record.sequence, record.identity)
            }
        }
        writer.flush()?.let(output::add)
        return output
    }
}
