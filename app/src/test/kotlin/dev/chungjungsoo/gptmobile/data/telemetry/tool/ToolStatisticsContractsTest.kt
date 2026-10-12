package dev.chungjungsoo.gptmobile.data.telemetry.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStatisticsContractsTest {
    @Test fun unknownAndCanceledAreExcludedFromSuccessRate() {
        var value = ToolRollup("tool")
        listOf(ToolOutcome.SUCCESS, ToolOutcome.FAILURE, ToolOutcome.UNKNOWN, ToolOutcome.CANCELED, ToolOutcome.PARTIAL).forEach {
            value = value.transition(null, ToolOutcome.IN_PROGRESS).transition(ToolOutcome.IN_PROGRESS, it)
        }
        assertEquals(5L, value.total)
        assertEquals(3L, value.determinate)
        assertEquals(100.0 / 3, value.successRate!!, 0.001)
        assertTrue(value.valid())
    }

    @Test fun lateReconciliationChangesOnlyOneCategory() {
        val initial = ToolRollup("tool").transition(null, ToolOutcome.IN_PROGRESS).transition(ToolOutcome.IN_PROGRESS, ToolOutcome.UNKNOWN)
        val updated = initial.transition(ToolOutcome.UNKNOWN, ToolOutcome.SUCCESS)
        assertEquals(1L, updated.total)
        assertEquals(0L, updated.unknown)
        assertEquals(1L, updated.success)
    }

    @Test fun histogramP95UsesOnlyRetainedLiveSamplesAndHasAnUnknownState() {
        assertNull(ToolRollup.p95UpperBound(listOf(ToolRollup("tool"))))
        var value = ToolRollup("tool")
        repeat(19) { value = value.transition(null, ToolOutcome.SUCCESS).withLatency(100) }
        value = value.transition(null, ToolOutcome.SUCCESS).withLatency(10000)
        assertEquals(200L, ToolRollup.p95UpperBound(listOf(value)))
        assertEquals(20L, value.liveSamples)
        assertTrue(value.valid())
        assertFalse(value.copy(latencyBuckets = "-1,0,0,0,0,0,0,0").valid())
    }

    @Test fun overflowAndInvalidCountersAreRejected() {
        assertFalse(ToolRollup("tool", total = -1).valid())
        assertFalse(ToolRollup("tool", total = Long.MAX_VALUE, success = Long.MAX_VALUE, failure = 1).valid())
        assertTrue(runCatching { ToolRollup("tool", total = Long.MAX_VALUE, success = Long.MAX_VALUE).transition(null, ToolOutcome.SUCCESS) }.isFailure)
        assertNull(ToolRollup("tool").successRate)
    }

    @Test fun sameNamedToolsOnDifferentConnectionsHaveDifferentIdentity() {
        assertTrue(ToolIdentity.of("search", "server-a").key != ToolIdentity.of("search", "server-b").key)
        assertEquals(ToolIdentity.of("search", "server-a").key, ToolIdentity.of("search", "server-a").key)
        assertEquals("amazon_free", ToolIdentity.of("amazon_search__free_native").plugin)
        assertEquals("local_memory", ToolIdentity.of("memory").plugin)
    }

    @Test fun unsupportedOrOrphanSnapshotsCannotReplaceHistory() {
        assertTrue(runCatching { ToolStatisticsSnapshot(3, emptyList(), emptyList(), emptyList(), emptyList()).validate() }.isFailure)
        assertTrue(runCatching { ToolStatisticsSnapshot(subjects = emptyList(), invocations = emptyList(), rollups = listOf(ToolRollup("missing")), attempts = emptyList()).validate() }.isFailure)
    }
}
