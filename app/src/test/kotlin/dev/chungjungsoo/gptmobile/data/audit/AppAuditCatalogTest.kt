package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditCatalogTest {
    @Test
    fun narrowLocalFixturesDoNotRemoveRenderingDatabaseOrDeviceQualification() = runBlocking {
        val source = object : AppAuditProbeSource {
            override val sourceId = "fixture"
            override suspend fun plan(options: AppAuditOptions) = listOf(
                AppAuditPlanEntry("fixture:contrast", sourceId, "contrast", "chat_ui", "Contrast fixture", sourceAreaIds = listOf("chat_ui", "accessibility_and_localization"), evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT),
                AppAuditPlanEntry("fixture:roundtrip", sourceId, "roundtrip", "persistence", "Evidence round trip", sourceAreaIds = listOf("persistence"), evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT)
            )
            override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation = error("Planning must not execute fixtures")
        }
        val run = AppAuditCatalog(setOf(source)).create(id = "qualification-plan", now = 1)
        val suites = run.plan.filter { it.evidenceKind == AppAuditEvidenceKind.EXTERNAL_SUITE }
        assertTrue(suites.flatMap { it.sourceAreaIds }.containsAll(listOf("chat_ui", "accessibility_and_localization", "persistence")))
        assertTrue(suites.all { it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED && it.safetyClass == AppAuditSafetyClass.EXTERNAL_ONLY })
        val device = run.plan.single { it.evidenceKind == AppAuditEvidenceKind.DEVICE_MEASUREMENT }
        assertEquals(AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED, device.prerequisite)
        assertEquals(AppAuditSafetyClass.EXTERNAL_ONLY, device.safetyClass)
        assertTrue(device.sourceAreaIds.containsAll(listOf("chat_ui", "local_inference", "native_bridge")))
        assertTrue(run.results.isEmpty())
        assertEquals(0, appAuditSummary(run).verified)
        assertFalse(run.lineCoverageAvailable)
    }
}
