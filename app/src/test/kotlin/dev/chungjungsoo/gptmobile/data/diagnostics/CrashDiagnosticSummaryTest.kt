package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticSummaryTest {
    @Test fun recorderContinuationPrefixesCannotSilentlyTruncateRetainedFrameCounts() {
        var failure: Throwable? = null
        repeat(8) {
            failure = IllegalStateException(null, failure).apply {
                stackTrace = Array(90) { index -> StackTraceElement("a", "b", "private.kt", index) }
            }
        }
        val safe = crashDiagnosticSummary(requireNotNull(failure), "main")
        val retained = safe.lineSequence().count { it.startsWith("at ") }
        val formatted = boundedDiagnosticRecord("2026-11-09T03:37:41Z F/AppCrash: " + "x".repeat(800), "x".repeat(64), safe)
        assertTrue(safe.contains("framesRetained=$retained"))
        assertTrue(retained in 8 until 240)
        assertFalse(formatted.contains("truncatedAtSource=true"))
        assertTrue(formatted.toByteArray(Charsets.UTF_8).size < DiagnosticLogFiles.MAX_RECORD_BYTES - 32)
        assertEquals(retained, Regex("(?:^| · )at a.b\\(").findAll(formatted).count())
    }

    @Test fun safeCauseMessageAndSuppressedApplicationFrameSurviveWithoutPrivateProse() {
        val cause = IllegalArgumentException("Failed requirement.").apply {
            stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.presentation.common.DiagnosticFrameTimingKt", "diagnosticFrameTiming", "DiagnosticFrameTiming.kt", 13))
        }
        val failure = IllegalStateException("private-message-canary", cause).apply {
            stackTrace = arrayOf(StackTraceElement("androidx.compose.Frame", "measure", "Frame.kt", 1))
            addSuppressed(
                IllegalStateException("suppressed-private-canary").apply {
                    stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.Cleanup", "release", "/data/private-canary.kt", 55))
                }
            )
        }
        val safe = crashDiagnosticSummary(failure, "main")
        assertTrue(safe.contains("message=Failed requirement."))
        assertTrue(safe.contains("relation=suppressed"))
        assertTrue(safe.contains("Cleanup.release(line=55"))
        assertTrue(safe.contains("suppressedCaptured=true"))
        assertTrue(safe.contains("stackCaptureComplete=true"))
        assertTrue(safe.contains("exceptionMessagesRetained=1"))
        assertTrue(safe.contains("exceptionMessagesOmitted=2"))
        assertFalse(safe.contains("private-canary"))
        assertFalse(safe.contains("private-message-canary"))
    }

    @Test fun applicationFrameBeyondFrameworkPrefixIsRetainedWithinTheSameFrameBudget() {
        val failure = IllegalArgumentException("Failed requirement.").apply {
            stackTrace = Array(90) { index ->
                if (index == 80) {
                    StackTraceElement("dev.chungjungsoo.gptmobile.Screen", "render", "Screen.kt", 80)
                } else {
                    StackTraceElement("androidx.compose.Frame", "measure", "Frame.kt", index)
                }
            }
        }
        val safe = crashDiagnosticSummary(failure, "main")
        assertTrue(safe.contains("Screen.render(line=80"))
        assertTrue(safe.contains("framesRetained=60"))
        assertTrue(safe.contains("framesOmitted=30"))
        assertEquals(safe, diagnosticMessageForStorage(safe))
    }

    @Test fun largeSuppressedGraphReportsItsActualCaptureLimit() {
        val failure = IllegalStateException("untrusted-private-canary").apply {
            stackTrace = emptyArray()
            repeat(20) { index ->
                addSuppressed(
                    IllegalStateException("nested-private-canary-$index").apply {
                        stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.Cleanup", "release", "Cleanup.kt", index))
                    }
                )
            }
        }
        val safe = crashDiagnosticSummary(failure, "main")
        assertTrue(safe.contains("throwablesRetained=8"))
        assertTrue(safe.contains("throwableGraphComplete=false"))
        assertTrue(safe.contains("suppressedCaptured=false"))
        assertTrue(safe.contains("stackCaptureComplete=false"))
        assertTrue(safe.length < 7800)
        assertFalse(safe.contains("private-canary"))
    }

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

    @Test fun frameworkOriginIsEvidenceAndDoesNotClaimTheApplicationIsBlameless() {
        val failure = SecurityException("protected vendor setting").apply {
            stackTrace = arrayOf(
                StackTraceElement("android.provider.Settings", "getString", "Settings.java", 81),
                StackTraceElement("com.android.internal.os.BinderInternal\$GcWatcher", "finalize", "BinderInternal.java", 92)
            )
        }
        val safe = crashDiagnosticSummary(failure, "FinalizerDaemon")
        assertTrue(safe.contains("topFrameOrigin=android_framework"))
        assertTrue(safe.contains("rootCauseAttribution=not_established"))
        assertTrue(safe.contains("BinderInternal\$GcWatcher.finalize"))
        assertFalse(safe.contains("protected vendor setting"))
    }
}
