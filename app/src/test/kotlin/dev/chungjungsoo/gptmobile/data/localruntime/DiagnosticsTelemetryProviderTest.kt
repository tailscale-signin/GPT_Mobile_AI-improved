package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTelemetryProviderTest {

    @Test
    fun formatDiagnosticsText_containsExpectedSections() {
        val snapshot = DiagnosticsTelemetryProvider.DiagnosticsSnapshot(
            backendName = "Gemma 3 1B",
            accelerator = "NPU / Hexagon HTP",
            socModel = "SM8750",
            totalRamGb = 12L,
            availableRamMb = 5400L,
            thermalStatus = "Nominal",
            batteryPct = 85,
            isCharging = true,
            qnnReady = true,
            dispatchDir = "/data/user/0/dev.melo.gptmobile.improved/no_backup/qnn_dispatch",
            skelExists = true
        )

        val text = DiagnosticsTelemetryProvider.formatDiagnosticsText(
            snapshot = snapshot,
            telemetryNotice = "Local: 18.4 tok/s · TTFT 412ms · ~240 tokens"
        )

        assertTrue(text.contains("=== Local Model Diagnostics ==="))
        assertTrue(text.contains("Engine Backend: Gemma 3 1B"))
        assertTrue(text.contains("Target Accelerator: NPU / HEXAGON HTP"))
        assertTrue(text.contains("Processor: SM8750"))
        assertTrue(text.contains("Available 5400 MB / Total 12 GB"))
        assertTrue(text.contains("Thermal State: Nominal"))
        assertTrue(text.contains("Battery: 85% (Charging)"))
        assertTrue(text.contains("QNN HTP Native Status: Ready"))
        assertTrue(text.contains("QNN Dispatch Dir: /data/user/0/dev.melo.gptmobile.improved/no_backup/qnn_dispatch"))
        assertTrue(text.contains("QNN Skel Present: Yes"))
        assertTrue(text.contains("Local: 18.4 tok/s · TTFT 412ms · ~240 tokens"))
    }
}
