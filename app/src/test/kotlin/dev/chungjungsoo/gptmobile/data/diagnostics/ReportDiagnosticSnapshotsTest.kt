package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDiagnosticSnapshotsTest {
    @Test fun selectionReturnsOnlyTheRequestedAreaAndKeepsTheNewestCapture() {
        var now = 1_000_000L
        val cache = ReportDiagnosticSnapshotCache { now }
        val provider = snapshot("PROVIDER", now, "profile_hash" to "a".repeat(64))
        cache.retain(provider)
        now += 100
        val tool = snapshot("TOOL", now, "tool_name_hash" to "b".repeat(64))
        cache.retain(tool)
        cache.retain(snapshot("TOOL", now - 50, "tool_name_hash" to "c".repeat(64)))
        assertEquals(provider, cache.latest("PROVIDER"))
        assertEquals(tool, cache.latest("TOOL"))
        assertNull(cache.latest("VOICE"))
        assertNull(cache.latest("provider"))
        cache.clear()
        assertNull(cache.latest("PROVIDER"))
        assertNull(cache.latest("TOOL"))
    }

    @Test fun staleFutureAndUncapturedValuesCannotBecomeManualEvidence() {
        var now = 1_000_000L
        val cache = ReportDiagnosticSnapshotCache { now }
        cache.retain(snapshot("APP", now + 1, "app_exit_kind" to "JVM_CRASH"))
        cache.retain(snapshot("APP", now - ReportDiagnosticSnapshotCache.MAX_AGE_MILLIS - 1, "app_exit_kind" to "JVM_CRASH"))
        cache.retain(requireNotNull(IssueReportDiagnostics.fromFields("MANUAL_REPORT", mapOf("diagnostic_area" to "APP", "diagnostic_capture_mode" to "NO_CAPTURE"), now)))
        assertNull(cache.latest("APP"))
        val current = snapshot("APP", now, "app_exit_kind" to "JVM_CRASH")
        cache.retain(current)
        assertEquals(current, cache.latest("APP", now + ReportDiagnosticSnapshotCache.MAX_AGE_MILLIS))
        now += ReportDiagnosticSnapshotCache.MAX_AGE_MILLIS + 1
        assertNull(cache.latest("APP"))
    }

    @Test fun restoredOrMutatedUnvalidatedPayloadsAreRejectedAndCopiesAreIndependent() {
        val now = 1_000_000L
        val cache = ReportDiagnosticSnapshotCache { now }
        val current = snapshot("PROVIDER", now, "output_tokens" to 7)
        cache.retain(current.copy(values = current.values + ("prompt" to JsonPrimitive("private"))))
        assertNull(cache.latest("PROVIDER"))
        val mutable = current.values.toMutableMap()
        cache.retain(current.copy(values = mutable))
        mutable["output_tokens"] = JsonPrimitive(900)
        assertEquals(JsonPrimitive(7), cache.latest("PROVIDER")?.values?.get("output_tokens"))
        assertTrue(requireNotNull(cache.latest("PROVIDER")).isValid())
    }

    @Test fun revokedCaptureCannotRepopulateAfterClear() {
        var allowed = true
        val cache = ReportDiagnosticSnapshotCache(canRetain = { allowed }, clock = { 1_000_000L })
        val captured = snapshot("VOICE", 1_000_000, "voice_phase" to "LISTENING")
        cache.retain(captured)
        assertEquals(captured, cache.latest("VOICE"))
        allowed = false
        cache.clear()
        cache.retain(captured)
        allowed = true
        assertNull(cache.latest("VOICE"))
    }

    @Test fun originTokensKeepTheirOriginalAuthorityAcrossPrivateAndPublicTransitions() {
        var allowed = false
        val cache = ReportDiagnosticSnapshotCache(canRetain = { allowed }, clock = { 1_000_000L })
        assertNull(cache.captureOrigin())
        allowed = true
        val origin = requireNotNull(cache.captureOrigin())
        val current = snapshot("APP", 1_000_000, "composer_focused" to true)
        cache.retain(current, origin)
        assertEquals(current, cache.latest("APP"))
        allowed = false
        assertFalse(cache.isCurrent(origin))
        allowed = true
        cache.retain(current, origin)
        assertFalse(cache.isCurrent(origin))
        assertNull(cache.latest("APP"))
        val fresh = requireNotNull(cache.captureOrigin())
        cache.retain(current, fresh)
        assertEquals(current, cache.latest("APP"))
    }

    @Test fun clearingFencesOldCallbacksEvenWhenTheyMissThePrivateInterval() {
        val cache = ReportDiagnosticSnapshotCache(clock = { 1_000_000L })
        val origin = requireNotNull(cache.captureOrigin())
        val current = snapshot("APP", 1_000_000, "composer_focused" to true)
        cache.clear()
        cache.retain(current, origin)
        assertNull(cache.latest("APP"))
        assertFalse(cache.isCurrent(origin))
        val fresh = requireNotNull(cache.captureOrigin())
        cache.retain(current, fresh)
        assertEquals(current, cache.latest("APP"))
    }

    @Test fun anotherCacheCannotSupplyAnOriginOrRepopulateThisArea() {
        val source = ReportDiagnosticSnapshotCache(clock = { 1_000_000L })
        val target = ReportDiagnosticSnapshotCache(clock = { 1_000_000L })
        val origin = requireNotNull(source.captureOrigin())
        target.retain(snapshot("APP", 1_000_000, "composer_focused" to true), origin)
        assertFalse(target.isCurrent(origin))
        assertNull(target.latest("APP"))
        assertTrue(source.isCurrent(origin))
    }

    @Test fun aSynchronousDebugOffOnPairRevokesCaptureWithoutAFlowCollector() {
        val wasEnabled = LocalDiagnosticsPolicy.enabled
        try {
            LocalDiagnosticsPolicy.setEnabled(true)
            val origin = requireNotNull(ReportDiagnosticSnapshots.captureOrigin())
            LocalDiagnosticsPolicy.setEnabled(false)
            LocalDiagnosticsPolicy.setEnabled(true)
            assertFalse(ReportDiagnosticSnapshots.isCurrent(origin))
            assertTrue(ReportDiagnosticSnapshots.isCurrent(requireNotNull(ReportDiagnosticSnapshots.captureOrigin())))
        } finally {
            LocalDiagnosticsPolicy.setEnabled(wasEnabled)
            ReportDiagnosticSnapshots.clear()
        }
    }

    private fun snapshot(area: String, time: Long, field: Pair<String, Any?>): IssueReportDiagnostics = requireNotNull(
        IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", mapOf("diagnostic_area" to area, "diagnostic_capture_mode" to "INCIDENT", field), time)
    )
}
