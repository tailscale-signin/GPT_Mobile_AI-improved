package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.writeAppAuditExport
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppAuditIdentityExportTest {
    @Test fun displayOnlyCurrentNameNeverEntersThePlanFingerprintOrAuditExport(): Unit = runBlocking {
        val entry = auditEntry("configured", AppAuditEvidenceKind.CONFIGURATION, "mcp_connection")
            .copy(probeId = "plugin_configuration", targetId = "connection:private-test-id", configFingerprint = "accepted-config-fingerprint")
        val run = auditRun(listOf(entry), listOf(auditResult(entry, source = AppAuditObservationSource.CONFIGURATION, reason = "PLUGIN_CONFIGURATION_ONLY")))
        val currentNames = appAuditCurrentTargetNames(emptyList(), listOf("private-test-id" to "Private workspace connection canary"))
        val presented = appAuditPresentation(run, currentNames).components.single().entries.single()
        assertEquals("Current saved name", "Private workspace connection canary", presented.currentSavedName)
        assertEquals("accepted-config-fingerprint", run.plan.single().configFingerprint)
        val batch = BenchmarkBatch(run.id, BenchmarkMode.APP_AUDIT, run.startedAtEpochMs, emptyList(), state = BenchmarkBatchState.COMPLETED, audit = run)
        val output = ByteArrayOutputStream()
        writeAppAuditExport(output, batch)
        val body = output.toString(Charsets.UTF_8.name())
        val check = Json.parseToJsonElement(body).jsonObject.getValue("checks").jsonArray.single().jsonObject
        assertEquals(presented.targetAlias, check.getValue("targetAlias").jsonPrimitive.content)
        assertFalse(body.contains("Private workspace connection canary"))
        assertFalse(body.contains("private-test-id"))
        assertFalse(body.contains("accepted-config-fingerprint"))
    }
}
