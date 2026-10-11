package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExecutionPlannerTest {
    private val key = RuntimeQualificationKey("device", "firmware", "runtime", "dispatch", "artifact", "gpu", contextTokens = 2048, samplerIdentity = "default")
    private val requested = LocalExecutionPlan("gpu", "artifact", "runtime", "gpu", contextTokens = 2048, tokenizerTemplateIdentity = "artifact")
    private val faster = requested.copy(id = "gpu-mtp", speculativeDecoding = true)
    private fun qualified() = RuntimeQualificationResult(key.copy(speculativeDecoding = true), faster, QualificationStatus.QUALIFIED, listOf("run"), completedRuns = 500, medianDecodeTokensPerSecond = 50.0, sustainedMedianDecodeTokensPerSecond = 45.0, p95FirstVisibleMs = 300, medianLoadMs = 100, qualityEquivalent = true, actualBackendConfirmed = true)
    private fun select(result: RuntimeQualificationResult) = LocalExecutionPlanner().select(LocalExecutionPolicy.SPEED, requested, key, listOf(result))

    @Test fun `selects a measured qualified faster plan`() {
        assertEquals(faster, select(qualified()).selected)
    }

    @Test fun `restored and changed firmware evidence cannot authorize a plan`() {
        assertEquals(requested, select(qualified().copy(status = QualificationStatus.HISTORICAL)).selected)
        assertEquals(requested, select(qualified().copy(key = key.copy(firmwareDigest = "old"))).selected)
        assertEquals(requested, select(qualified().copy(actualBackendConfirmed = false)).selected)
    }

    @Test fun `quality cohort and unavailable counters are rejected`() {
        assertEquals(requested, select(qualified().copy(qualityEquivalent = false)).selected)
        assertEquals(requested, select(qualified().copy(medianDecodeTokensPerSecond = Double.NaN)).selected)
        assertEquals(requested, select(qualified().copy(key = key.copy(samplerIdentity = "different"))).selected)
    }

    @Test fun `battery policy requires measured energy and profile preserves user choice`() {
        assertEquals(requested, LocalExecutionPlanner().select(LocalExecutionPolicy.BATTERY, requested, key, listOf(qualified())).selected)
        assertEquals(requested, LocalExecutionPlanner().select(LocalExecutionPolicy.PROFILE, requested, key, listOf(qualified())).selected)
    }

    @Test fun `warm switch cost prevents a slower short answer`() {
        val warm = qualified().copy(plan = requested, key = key, medianDecodeTokensPerSecond = 40.0)
        val cold = qualified().copy(medianLoadMs = 20_000)
        assertEquals(requested, LocalExecutionPlanner().select(LocalExecutionPolicy.AUTO, requested, key, listOf(warm, cold), expectedOutputTokens = 20, warmPlanId = requested.id).selected)
    }

    @Test fun `speed requires sustained evidence and explicit MTP off is preserved`() {
        assertEquals(requested, select(qualified().copy(sustainedMedianDecodeTokensPerSecond = null)).selected)
        val off = requested.copy(speculativeDecoding = false)
        assertEquals(off, LocalExecutionPlanner().select(LocalExecutionPolicy.SPEED, off, key.copy(speculativeDecoding = false), listOf(qualified())).selected)
    }

    @Test fun `native experiments cannot be activated as accelerator strings`() {
        assertNull(LocalExecutionExperiments.unavailableReason(LocalExecutionMode.SINGLE))
        assertTrue(LocalExecutionMode.entries.filter { it != LocalExecutionMode.SINGLE }.all { LocalExecutionExperiments.unavailableReason(it) != null })
    }
}
