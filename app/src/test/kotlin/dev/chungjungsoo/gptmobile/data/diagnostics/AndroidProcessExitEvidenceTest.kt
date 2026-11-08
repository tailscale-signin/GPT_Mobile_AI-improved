package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.ApplicationExitInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProcessExitEvidenceTest {
    @Test fun userRequestedExitIsNotAnAppCrashEvenBesideAPlatformFinalizerException() {
        val exit = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:08:08Z I/ProcessExit: reasonCode=10 · processId=42 · workerExitConfirmed=true", "android_exit_history")).jsonObject
        assertEquals(JsonPrimitive("REASON_USER_REQUESTED"), exit["osExitReasonName"])
        assertEquals(JsonPrimitive(false), exit["processCrashConfirmed"])
        assertEquals(JsonPrimitive(false), exit["countAsIssue"])
        val exception = classifyDiagnostic("E", "Android", "E/System (42): Uncaught exception thrown by finalizer")
        assertEquals("platform_finalizer_exception", exception.category)
        assertTrue(exception.actionable)
        assertFalse(exception.category == "fatal")
    }

    @Test fun actualCrashAndAnrReasonsRemainFailuresWhileSignalCauseStaysUnknown() {
        fun exit(reason: Int) = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:08:08Z I/ProcessExit: reasonCode=$reason", "android_exit_history")).jsonObject
        assertEquals(JsonPrimitive(true), exit(ApplicationExitInfo.REASON_CRASH)["processCrashConfirmed"])
        assertEquals(JsonPrimitive(true), exit(ApplicationExitInfo.REASON_CRASH_NATIVE)["countAsIssue"])
        assertEquals(JsonPrimitive("historical_anr"), exit(ApplicationExitInfo.REASON_ANR)["category"])
        assertEquals(JsonNull, exit(ApplicationExitInfo.REASON_SIGNALED)["processCrashConfirmed"])
        assertEquals(JsonPrimitive(false), exit(ApplicationExitInfo.REASON_USER_STOPPED)["processCrashConfirmed"])
    }
}
