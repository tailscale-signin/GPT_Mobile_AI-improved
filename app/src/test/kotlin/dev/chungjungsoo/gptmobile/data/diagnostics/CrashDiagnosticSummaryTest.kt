package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticSummaryTest {
    @Test fun frameworkOnlyCrashPreservesMethodsAndCauseWithoutPrivateMessagesOrPaths() {
        val cause = IllegalArgumentException("secret prompt and token").apply {
            stackTrace = arrayOf(StackTraceElement("androidx.compose.ui.node.LayoutNode", "requireCoordinates", "/data/user/private.kt", 28))
        }
        val failure = IllegalStateException("private conversation", cause).apply {
            stackTrace = arrayOf(StackTraceElement("androidx.compose.ui.layout.LayoutCoordinates", "localToRoot", "/storage/private.kt", 42))
        }
        val safe = crashDiagnosticSummary(failure, "main")
        assertTrue(safe.contains("androidx.compose.ui.layout.LayoutCoordinates.localToRoot"))
        assertTrue(safe.contains("androidx.compose.ui.node.LayoutNode.requireCoordinates"))
        assertTrue(safe.contains("type=java.lang.IllegalArgumentException"))
        assertTrue(safe.contains("framesRetained=2"))
        assertTrue(safe.contains("stackCaptureComplete=true"))
        assertFalse(safe.contains("secret prompt"))
        assertFalse(safe.contains("private conversation"))
        assertFalse(safe.contains("/data/"))
        assertFalse(safe.contains("/storage/"))
    }

    @Test fun boundedStackCaptureReportsOmittedFrames() {
        val failure = IllegalStateException().apply {
            stackTrace = Array(90) { StackTraceElement("androidx.compose.Frame", "measure", "Frame.kt", it) }
        }
        val safe = crashDiagnosticSummary(failure, "main")
        assertTrue(safe.contains("framesTotal=90"))
        assertTrue(safe.contains("framesRetained=60"))
        assertTrue(safe.contains("framesOmitted=30"))
        assertTrue(safe.contains("stackCaptureComplete=false"))
    }

    @Test fun longMethodNamesCannotMakeThePersistedCompletenessClaimFalse() {
        val failure = IllegalStateException().apply {
            stackTrace = Array(90) { StackTraceElement("androidx.compose." + "LongClass".repeat(40), "longMethod".repeat(40), "private.kt", it) }
        }
        val safe = crashDiagnosticSummary(failure, "thread".repeat(50))
        val retained = safe.lineSequence().count { it.startsWith("at ") }
        assertTrue(retained in 1..59)
        assertTrue(safe.contains("framesRetained=$retained"))
        assertTrue(safe.contains("framesOmitted=${90 - retained}"))
        assertTrue(safe.contains("stackCaptureComplete=false"))
        assertTrue(safe.length < 7800)
        assertEquals(safe, diagnosticMessageForStorage(safe))
    }
}
