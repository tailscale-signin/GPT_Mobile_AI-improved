package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIncidentTrackerTest {
    @Test fun traceRowsKeepOneOccurrenceButANewCrashRemainsDistinct() {
        val tracker = DiagnosticIncidentTracker()
        val rows = (0 until 142).map { i -> tracker.accept("2026-10-14T02:05:45.758Z F/DEBUG: ${if (i == 0) "Fatal signal 6" else "native frame $i"}", "e$i", null) }
        assertEquals(142, rows.size)
        assertEquals(1, rows.count { it.first })
        assertEquals(1, rows.map { it.id }.distinct().size)
        val later = tracker.accept("2026-10-14T02:06:00Z F/DEBUG: Fatal signal 6", "later", null)
        assertTrue(later.first)
        assertEquals("later", later.id)
    }

    @Test fun optionalCompatibilityProbeIsNotAUserFailureAndContinuationsStayAssociated() {
        assertFalse(classifyDiagnostic("W", "MCP", "DISCOVERY_NEGOTIATION · status=405 · outcome=legacy_fallback").actionable)
        val tracker = DiagnosticIncidentTracker()
        tracker.accept("2026-10-14T02:05:45Z E/AppCrash: type=java.lang.IllegalStateException", "crash", null)
        assertEquals(DiagnosticIncidentTracker.Association("crash", false), tracker.accept("  at app.frame()", "frame", "crash"))
        assertTrue(classifyDiagnostic("I", "Tool", "outcome=FAILED").actionable)
    }

    @Test fun oneOperationKeepsFailureRetryAndRecoveryTogetherButAnotherDispatchIsDistinct() {
        val tracker = DiagnosticIncidentTracker()
        val start = tracker.accept("2026-10-24T10:00:00Z I/Runtime: LOAD_START · nativeOperationId=load_4:7 · workerGenerationId=worker-2", "start", null)
        val failed = tracker.accept("2026-10-24T10:00:01Z I/Runtime: LOAD_FAILED · nativeOperationId=load_4:7 · workerGenerationId=worker-2 · stage=load", "failure", null)
        val retry = tracker.accept("2026-10-24T10:00:02Z E/Benchmark: retained failure · nativeOperationId=load_4:7 · workerGenerationId=worker-2", "consequence", null)
        val recovered = tracker.accept("2026-10-24T10:00:03Z I/Runtime: RECOVERED · nativeOperationId=load_4:7 · workerGenerationId=worker-2", "recovery", null)
        assertFalse(start.first)
        assertTrue(failed.first)
        assertFalse(retry.first)
        assertFalse(recovered.first)
        assertEquals(listOf(start.id), listOf(start, failed, retry, recovered).map { it.id }.distinct())
        val second = tracker.accept("2026-10-24T10:00:04Z I/Runtime: LOAD_FAILED · nativeOperationId=load_4:8 · workerGenerationId=worker-2", "second", null)
        assertTrue(second.first)
        org.junit.Assert.assertNotEquals(failed.id, second.id)
    }

    @Test fun namespacedIncidentIdsAreNotTruncatedAndContinuationUsesTheActualParent() {
        val tracker = DiagnosticIncidentTracker()
        val root = tracker.accept("2026-10-24T10:00:00Z E/Runtime: failed · incidentId=scope:request_1", "root", null)
        assertEquals("scope:request_1", root.id)
        assertEquals(DiagnosticIncidentTracker.Association(root.id, false), tracker.accept("  at worker()", "frame", "root"))
        val other = tracker.accept("2026-10-24T10:00:00Z E/Runtime: failed · incidentId=scope:request_2", "other", null)
        assertTrue(other.first)
        org.junit.Assert.assertNotEquals(root.id, other.id)
    }

    @Test fun nativeHistoryAndKotlinReceiptAliasesShareOneWorkerScopedIncident() {
        val tracker = DiagnosticIncidentTracker()
        val native = tracker.accept("2026-10-24T10:00:00Z I/LocalRuntimeIncident: LAST_NATIVE_ERROR · operationId=42 · workerGenerationId=worker-a · stage=OPENCL_clBuildProgram_BEGIN", "native", null)
        val kotlin = tracker.accept("2026-10-24T10:00:01Z I/Runtime: LOAD_FAILED · nativeOperationId=42 · workerGenerationId=worker-a · runtimeRequestId=request-a", "kotlin", null)
        val recovery = tracker.accept("2026-10-24T10:00:02Z I/Runtime: NATIVE_CLEANUP_RECEIPT · nativeOperationId=42 · workerGenerationId=worker-a · workerExitConfirmed=true", "cleanup", null)
        assertTrue(native.first)
        assertEquals(native.id, kotlin.id)
        assertEquals(native.id, recovery.id)
        assertFalse(kotlin.first)
        assertFalse(recovery.first)
        val replacement = tracker.accept("2026-10-24T10:00:03Z I/Runtime: LOAD_FAILED · nativeOperationId=42 · workerGenerationId=worker-b", "replacement", null)
        assertTrue(replacement.first)
        org.junit.Assert.assertNotEquals(native.id, replacement.id)
    }
}
