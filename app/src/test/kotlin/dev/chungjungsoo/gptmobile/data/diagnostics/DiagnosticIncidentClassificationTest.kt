package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIncidentClassificationTest {
    @Test fun incompleteTasksRemainActionableWithoutMisclassifyingUserCancellation() {
        for (status in listOf("PARTIAL", "BLOCKED", "EVIDENCE_MISSING")) {
            val result = classifyDiagnostic("I", "Agent", "RUN_TERMINAL · runId=run-1 · status=$status · reason=TASK_INCOMPLETE")
            assertTrue(result.actionable)
            assertEquals("task_incomplete", result.category)
        }
        assertFalse(classifyDiagnostic("I", "Agent", "RUN_TERMINAL · status=CANCELED · reason=USER_WHOLE_RUN").actionable)
    }

    @Test fun transportFallbackPreservesComponentAndRecoveryClassifications() {
        assertEquals("transport_availability", classifyDiagnostic("I", "Gateway", "Unexpected end of stream").category)
        assertEquals("native_operation_failure", classifyDiagnostic("I", "LocalModel", "SEND_FAILED · unexpected end of stream").category)
        assertEquals("mcp_connectivity", classifyDiagnostic("I", "MCP", "Health · state=UNREACHABLE · error=Failed to connect").category)
        assertFalse(classifyDiagnostic("I", "Network", "CONNECTIVITY_RECOVERED · previous=unexpected end of stream").actionable)
    }

    @Test fun platformCleanupIsNotConfusedWithApplicationFailures() {
        assertFalse(classifyDiagnostic("E", "Android", "E/ImeBackDispatcher(123): Ime callback not found. Ignoring unregisterReceivedCallback. callbackId: 42").actionable)
        assertFalse(classifyDiagnostic("W", "Android", "W/InteractionJankMonitor(123): Initializing without READ_DEVICE_CONFIG permission. enabled=false, interval=1").actionable)
        assertTrue(classifyDiagnostic("E", "Android", "E/ImeBackDispatcher(123): Failed to open input connection").actionable)
        val disconnected = classifyDiagnostic("W", "MCP", "Health · state=UNREACHABLE · failures=10 · error=Failed to connect")
        assertEquals("mcp_connectivity", disconnected.category)
        assertTrue(disconnected.actionable)
    }

    @Test fun isolatedHighRefreshMissesRemainSamplesWhileStallsRemainActionable() {
        val sparse = classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=824 · jankFrames=1 · maxMs=15.597008 · stallsOver100Ms=0 · droppedSamples=0")
        assertFalse(sparse.actionable)
        assertEquals("performance_sample", sparse.category)
        assertTrue(classifyDiagnostic("E", "Rendering", "FRAME_TIMING · frames=824 · jankFrames=0").actionable)
        assertTrue(classifyDiagnostic("W", "Rendering", "FRAME_TIMING · frames=824 · jankFrames=0").actionable)
        assertTrue(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=824 · jankFrames=1 · maxMs=220.0 · stallsOver100Ms=1").actionable)
        assertTrue(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=120 · jankFrames=6").actionable)
        assertTrue(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=824 · jankFrames=0 · droppedSamples=1").actionable)
    }

    @Test fun `durable crash recorded at info severity is still a critical issue`() {
        val result = classifyDiagnostic("I", "AppCrash", "eventId=abc · type=java.lang.IndexOutOfBoundsException · thread=main")
        assertTrue(result.actionable)
        assertEquals("critical", result.severity)
    }

    @Test fun `current rendering counters and Android skipped frames count as performance issues`() {
        assertTrue(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=120 · jankFrames=8").actionable)
        assertFalse(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=120 · jankFrames=0").actionable)
        assertEquals("performance", classifyDiagnostic("I", "Android", "I/Choreographer: Skipped 35 frames! The application may be doing too much work on its main thread.").category)
    }

    @Test fun codecDiscoveryIsInformationalButGraphicsFailuresRemainActionable() {
        assertFalse(classifyDiagnostic("W", "Android", "W/AudioCapabilities(22965): Unsupported mediaType audio/alac").actionable)
        assertFalse(classifyDiagnostic("W", "Android", "W/VideoCapabilities(22965): Unsupported mime image/vnd.android.heic").actionable)
        assertEquals("graphics_driver_failure", classifyDiagnostic("I", "Android", "I/AdrenoVK-0(22965): Shader compilation failed for shaderType: 0").category)
        assertTrue(classifyDiagnostic("I", "Android", "I/AdrenoVK-0(22965): Pipeline create failed").actionable)
        assertTrue(classifyDiagnostic("E", "LocalModel", "Unsupported backend").actionable)
    }

    @Test fun successfulConnectionSettingsDoNotCountAsTimeoutAndStructuredFailuresDo() {
        assertFalse(classifyDiagnostic("I", "MCP", "eventId=abc · Connect start · timeout=8000").actionable)
        assertFalse(classifyDiagnostic("I", "MCP", "eventId=abc · DISCOVERY_NEGOTIATION · outcome=legacy_fallback").actionable)
        assertTrue(classifyDiagnostic("I", "Research", "eventId=abc · MODEL · outcome=INVALID_RESPONSE").actionable)
        assertTrue(classifyDiagnostic("I", "Research", "eventId=abc · DELIVERY_VALIDATED · accepted=false").actionable)
        assertTrue(classifyDiagnostic("I", "Combined", "eventId=abc · SYNTHESIS_QUALITY · status=CORRECTION_REQUIRED").actionable)
    }

    @Test fun causalModelAndDelegationRowsCountOneIncident() {
        val tracker = DiagnosticIncidentTracker()
        val model = tracker.accept("2026-10-19T12:00:00Z W/Model: Finished inv · incidentId=inv-1 · status=FAILED", "event-a", null)
        val child = tracker.accept("2026-10-19T12:00:01Z E/Delegation: Child returned · incidentId=inv-1 · status=FAILED", "event-b", null)
        assertEquals("inv-1", model.id)
        assertTrue(model.first)
        assertEquals(model.id, child.id)
        assertFalse(child.first)
    }

    @Test fun capturedAndroidTagSurvivesRecorderPrefixNormalizationForClassification() {
        val prefix = "eventId=record-1 · monoNs=42 · bootMs=9 · processId=123 · processName=app · "
        assertEquals("platform_codec_capability", classifyDiagnostic("W", "Android", prefix + "W/AudioCapabilities(123): Unsupported mime audio/example").category)
        assertEquals("graphics_driver_failure", classifyDiagnostic("I", "Android", prefix + "I/AdrenoVK-0(123): Pipeline create failed").category)
        assertEquals("performance", classifyDiagnostic("I", "Android", prefix + "I/Choreographer(123): Skipped 35 frames!").category)
        assertEquals("platform_job_hint", classifyDiagnostic("W", "Android", prefix + "W/JobInfo(123): Requested important-while-foreground flag for job116 is ignored and takes no effect").category)
        assertEquals("Pipeline create failed", diagnosticEventMessage(prefix + "I/AdrenoVK-0(123): Pipeline create failed"))
    }

    @Test fun recognitionRateLimitIsNotReportedAsAPermissionFailure() {
        val limited = classifyDiagnostic("W", "VoiceRecognition", "recognition_error=10 · locale=en-US · onDevice=true")
        val permission = classifyDiagnostic("W", "VoiceRecognition", "recognition_error=9 · locale=en-US · onDevice=true")
        assertEquals("speech_rate_limit", limited.category)
        assertEquals("speech_permission", permission.category)
        assertTrue(limited.actionable)
        assertTrue(permission.actionable)
        assertEquals("warning", classifyDiagnostic("W", "VoiceRecognition", "recognition_error=100 · locale=en-US").category)
    }

    @Test fun vendorSettingErrorsRequireAttributionAndNeverHideACrash() {
        val platform = classifyDiagnostic("E", "Android", "E/System: Settings key: <asus_leak_detect_proj> is not readable")
        assertEquals("protected_settings_access", platform.category)
        assertEquals("error", platform.severity)
        assertTrue(platform.actionable)
        val fatal = classifyDiagnostic("F", "Android", "F/AndroidRuntime: FATAL EXCEPTION in finalizer: asus_leak_detect_proj")
        assertEquals("fatal", fatal.category)
        assertEquals("critical", fatal.severity)
        assertEquals("fatal", classifyDiagnostic("I", "AppCrash", "asus_leak_detect_proj").category)
    }

    @Test fun schedulerAndUnboundSpeechWarningsRemainActionableWithoutInventingFailures() {
        assertEquals("work_scheduler_reconciliation", classifyDiagnostic("W", "Android", "W/WM: Didn't find WorkSpec for id abc").category)
        assertEquals("network_callback_lifecycle", classifyDiagnostic("W", "Android", "W/ConnectivityManager: callback not found for CALLBACK_AVAILABLE message").category)
        assertEquals("speech_engine_lifecycle", classifyDiagnostic("W", "Android", "W/TextToSpeech: stop failed: not bound to TTS engine").category)
        assertEquals("fatal", classifyDiagnostic("F", "Android", "Feedback manager requires a model with a single signature").category)
    }
}
