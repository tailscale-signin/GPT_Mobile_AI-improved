package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPublicationTest {
    @Test fun progressAndSystemErrorsDoNotConsumeTheFirstAnswerMilestone() {
        val tracker = OutputPublicationTracker()
        tracker.observe(OutputLineage(OutputOrigin.PROGRESS_NOTICE), 10)
        tracker.observe(OutputLineage(OutputOrigin.SYSTEM_ERROR, "rejected"), 304)
        tracker.observe(OutputLineage(OutputOrigin.MODEL_TEXT, "accepted"), 20)
        val first = tracker.drain()
        assertEquals(listOf(OutputPublicationKind.FIRST_PROGRESS, OutputPublicationKind.FIRST_SYSTEM_ERROR, OutputPublicationKind.FIRST_VISIBLE_ANSWER), first.map { it.kind })
        assertEquals("accepted", first.last().lineage.invocationId)
        tracker.observe(OutputLineage(OutputOrigin.MODEL_TEXT, "later"), 100)
        assertTrue(tracker.drain().isEmpty())
    }

    @Test fun reportsRetainedTextAndUnknownTextAreNotModelAnswerMilestones() {
        val tracker = OutputPublicationTracker()
        listOf(OutputOrigin.APPLICATION_REPORT, OutputOrigin.RETAINED_CONTENT, OutputOrigin.UNKNOWN).forEach { tracker.observe(OutputLineage(it), 20) }
        assertEquals(listOf(OutputPublicationKind.FIRST_APPLICATION_REPORT, OutputPublicationKind.FIRST_RETAINED_CONTENT, OutputPublicationKind.FIRST_UNATTRIBUTED_OUTPUT), tracker.drain().map { it.kind })
    }

    @Test fun conflictingOrUnknownFragmentOriginsNeverAdoptTheLastInvocation() {
        val first = OutputLineage(OutputOrigin.MODEL_TEXT, "first")
        val second = OutputLineage(OutputOrigin.MODEL_TEXT, "second")
        val lineage = OutputLineageAccumulator()
        lineage.append(first)
        lineage.append(first)
        assertEquals(first, lineage.lineage)
        lineage.append(second)
        lineage.append(second)
        assertEquals(OutputLineage(), lineage.lineage)
        lineage.reset()
        lineage.append(OutputLineage())
        lineage.append(first)
        assertEquals(OutputLineage(), lineage.lineage)
    }
}
