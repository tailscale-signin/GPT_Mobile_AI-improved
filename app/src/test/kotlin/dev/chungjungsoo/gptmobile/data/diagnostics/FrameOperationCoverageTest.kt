package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOperationCoverageTest {
    @Test fun repeatedTextDigestRetainsTheChangedLayoutInputsWithoutSourceText() {
        val coverage = FrameOperationCoverage()
        val owner = CombinedOperationOwner(messageId = 7, digest = "a".repeat(64))
        val first = TextLayoutDiagnosticContext(0, 400, 0, Int.MAX_VALUE, 2f, 1f, false)
        val changed = first.copy(maxWidth = 280, fontScale = 1.5f)
        coverage.observe(FrameOperationCoverage.Sample("text_measure", 1, 10, 20, null, owner.copy(textLayoutContext = first)))
        coverage.observe(FrameOperationCoverage.Sample("text_measure", 2, 30, 40, null, owner.copy(textLayoutContext = changed)))

        val contexts = coverage.snapshot(0, 50).contextReport().contexts.map { it.jsonObject["textLayout"]!!.jsonObject }

        assertEquals(setOf(JsonPrimitive(400), JsonPrimitive(280)), contexts.map { it["maxWidth"] }.toSet())
        assertEquals(setOf(JsonPrimitive(1f), JsonPrimitive(1.5f)), contexts.map { it["fontScale"] }.toSet())
        assertTrue(contexts.all { it.keys == setOf("minWidth", "maxWidth", "minHeight", "maxHeight", "density", "fontScale", "rightToLeft") })
        assertFalse(TextLayoutDiagnosticContext(0, 10, 0, 10, Float.NaN, Float.POSITIVE_INFINITY, false).fields().containsKey("density"))
    }

    @Test fun sampledUiWorkRetainsMessageRevisionBlockAndExistingCounts() {
        val owner = CombinedOperationOwner(
            screen = "Chat",
            messageId = 42,
            runId = "run-1",
            revisionId = "revision-2",
            blockId = "3",
            profileUid = "profile-1",
            uiContext = UiOperationContext("Assistant", itemCount = 4, blockCount = 8)
        )
        val coverage = FrameOperationCoverage()
        coverage.observe(FrameOperationCoverage.Sample("ui_Chat_Assistant_measure", 7, 10, 8_000_010, owner.runId, owner))
        val report = coverage.snapshot(0, 9_000_000).contextReport()
        val context = report.contexts.single().jsonObject
        assertFalse(report.truncated)
        assertEquals(JsonPrimitive(7), context["traceId"])
        assertEquals(JsonPrimitive(8_000_000L), context["durationNs"])
        assertEquals(JsonPrimitive(42), context["message"])
        assertEquals(JsonPrimitive("run-1"), context["run"])
        assertEquals(JsonPrimitive("revision-2"), context["revision"])
        assertEquals(JsonPrimitive("3"), context["block"])
        assertEquals(JsonPrimitive("Assistant"), context["ui"]!!.jsonObject["component"])
        assertEquals(JsonPrimitive(4), context["ui"]!!.jsonObject["itemCount"])
        assertEquals(JsonPrimitive(8), context["ui"]!!.jsonObject["blockCount"])
    }

    @Test fun emittedContextsKeepTheNewestBoundedSetAndReportOmissions() {
        val coverage = FrameOperationCoverage()
        repeat(FrameOperationCoverage.MAX_REPORTED_CONTEXTS + 5) { index ->
            val owner = CombinedOperationOwner(messageId = index + 1, uiContext = UiOperationContext("Assistant"))
            coverage.observe(FrameOperationCoverage.Sample("ui_Chat_Assistant_measure", index, 10, 20, null, owner))
        }
        val snapshot = coverage.snapshot(0, 30)
        val report = snapshot.contextReport()
        assertFalse(snapshot.truncated)
        assertTrue(report.truncated)
        assertEquals(FrameOperationCoverage.MAX_REPORTED_CONTEXTS, report.contexts.size)
        assertEquals(JsonPrimitive(FrameOperationCoverage.MAX_REPORTED_CONTEXTS + 4), report.contexts.first().jsonObject["traceId"])
    }

    @Test fun contextCharacterBudgetUsesTheEscapedPayloadAndMarksTruncation() {
        val coverage = FrameOperationCoverage()
        repeat(20) { index ->
            val owner = CombinedOperationOwner(
                runId = "\"\\".repeat(160),
                revisionId = "r".repeat(500),
                blockId = "b".repeat(500),
                profileUid = "p".repeat(500),
                uiContext = UiOperationContext("LogRows", itemCount = 100, retainedItemCount = 200, eventCount = 300, pageIndex = 0, frozen = true)
            )
            coverage.observe(FrameOperationCoverage.Sample("ui_DebugLogs_LogRows_draw", index, 10, 20, owner.runId, owner))
        }
        val report = coverage.snapshot(0, 30).contextReport()
        assertTrue(report.truncated)
        assertTrue(report.contexts.isNotEmpty())
        assertTrue(report.contexts.size < FrameOperationCoverage.MAX_REPORTED_CONTEXTS)
        assertTrue(report.contexts.toString().length <= FrameOperationCoverage.MAX_CONTEXT_CHARACTERS)
        assertEquals(JsonPrimitive("r".repeat(160)), report.contexts.first().jsonObject["revision"])
        val counts = report.contexts.first().jsonObject["ui"]!!.jsonObject
        assertEquals(JsonPrimitive(100), counts["itemCount"])
        assertEquals(JsonPrimitive(300), counts["eventCount"])
        assertEquals(JsonPrimitive(true), counts["frozen"])
    }

    @Test fun defaultSampleCapacityAndClearAlsoBoundContextHistory() {
        val coverage = FrameOperationCoverage()
        val generation = coverage.generation()
        val owner = CombinedOperationOwner(messageId = 7, uiContext = UiOperationContext("Assistant"))
        repeat(129) { index -> coverage.observe(FrameOperationCoverage.Sample("ui_Chat_Assistant_draw", index, 10, 20, null, owner)) }
        val snapshot = coverage.snapshot(0, 30)
        assertEquals(128, snapshot.samples.size)
        assertTrue(snapshot.contextReport().truncated)
        coverage.clear()
        assertFalse(coverage.observe(FrameOperationCoverage.Sample("ui_Chat_Assistant_draw", 130, 10, 20, null, owner), generation))
        assertTrue(coverage.snapshot(0, 30).contextReport().contexts.isEmpty())
    }

    @Test fun endedWorkRemainsVisibleInTheMatchingFrameWindow() {
        val coverage = FrameOperationCoverage()
        coverage.observe(FrameOperationCoverage.Sample("text_measure", 1, 10, 30, "run-1"))
        coverage.observe(FrameOperationCoverage.Sample("checkpoint", 2, 40, 80, "run-2"))
        assertEquals(listOf(1), coverage.snapshot(0, 40).samples.map { it.traceId })
        assertEquals(listOf(2), coverage.snapshot(40, 90).samples.map { it.traceId })
        assertTrue(coverage.snapshot(90, 100).samples.isEmpty())
    }

    @Test fun overlappingWorkIsRetainedWithoutInventingAnOperationForAnEmptyWindow() {
        val coverage = FrameOperationCoverage()
        coverage.observe(FrameOperationCoverage.Sample("checkpoint", 1, 10, 90, null))
        assertEquals(1, coverage.snapshot(30, 60).samples.size)
        assertTrue(coverage.snapshot(100, 120).samples.isEmpty())
        coverage.observe(FrameOperationCoverage.Sample("invalid", 2, 70, 60, null))
        assertEquals(1, coverage.snapshot(0, 100).samples.size)
    }

    @Test fun evictionAndClearCannotClaimCompleteCoverage() {
        val coverage = FrameOperationCoverage(1)
        coverage.observe(FrameOperationCoverage.Sample("first", 1, 10, 20, null))
        coverage.observe(FrameOperationCoverage.Sample("second", 2, 30, 40, null))
        assertTrue(coverage.snapshot(0, 50).truncated)
        assertFalse(coverage.snapshot(21, 50).truncated)
        coverage.clear()
        assertFalse(coverage.snapshot(0, 50).truncated)
        assertTrue(coverage.snapshot(0, 50).samples.isEmpty())
    }

    @Test fun clearingHistoryRejectsAnOperationThatStartedBeforeTheClear() {
        val coverage = FrameOperationCoverage()
        val oldGeneration = coverage.generation()
        assertTrue(coverage.observe(FrameOperationCoverage.Sample("first", 1, 10, 20, "old-run"), oldGeneration))
        coverage.clear()
        assertFalse(coverage.observe(FrameOperationCoverage.Sample("in_flight", 2, 15, 40, "old-run"), oldGeneration))
        assertTrue(coverage.snapshot(0, 50).samples.isEmpty())
        assertTrue(coverage.observe(FrameOperationCoverage.Sample("new", 3, 30, 40, "new-run"), coverage.generation()))
        assertEquals(listOf("new-run"), coverage.snapshot(0, 50).samples.map { it.runId })
    }
}
