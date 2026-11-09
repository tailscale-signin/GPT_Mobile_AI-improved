package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditSelectedModelProbeSourceTest {
    @Test
    fun selectedProfilesPlanSeparateConfigurationAndUnexecutedGeneration() = runBlocking {
        var selected = emptyList<String>()
        val source = AppAuditSelectedModelProbeSource(
            inventory = { ids ->
                selected = ids
                ids.map { target(it) }
            },
            lookup = { error("Planning must not look up or execute a model") }
        )
        val entries = source.plan(AppAuditOptions(allowNetworkReads = true, selectedProfileIds = listOf("b", "a", "a")))
        assertEquals(listOf("a", "b"), selected)
        assertEquals(4, entries.size)
        val generation = entries.filter { it.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION }
        assertEquals(setOf("model:a", "model:b"), generation.map { it.targetId }.toSet())
        assertTrue(generation.all { it.safetyClass == AppAuditSafetyClass.EXTERNAL_ONLY && it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED })
        generation.forEach { entry ->
            val result = source.execute(entry, context())
            assertEquals(AppAuditOutcome.UNTESTED, result.outcome)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
            assertEquals("MODEL_BENCHMARK_REQUIRED", result.reasonCode)
        }
    }

    @Test
    fun exactConfigurationPassDoesNotClaimGenerationAndChangedTargetBlocks() = runBlocking {
        var current: AppAuditSelectedModelTarget? = target("selected")
        val source = AppAuditSelectedModelProbeSource({ listOfNotNull(current) }, { uid ->
            assertEquals("selected", uid)
            current
        })
        val entry = source.plan(AppAuditOptions(selectedProfileIds = listOf("selected"))).first()
        val result = source.execute(entry, context())
        assertEquals(AppAuditOutcome.PASS, result.outcome)
        assertEquals(AppAuditEvidenceKind.CONFIGURATION, result.evidenceKind)
        assertEquals(AppAuditObservationSource.CONFIGURATION, result.observationSource)
        assertFalse(result.configChanged)
        current = target("selected").copy(fingerprint = "changed", configurationRevision = "b".repeat(64))
        val changed = source.execute(entry, context())
        assertEquals(AppAuditOutcome.BLOCKED, changed.outcome)
        assertEquals("MODEL_CONFIGURATION_CHANGED", changed.reasonCode)
        assertTrue(changed.configChanged)
        current = null
        assertEquals("MODEL_CONFIGURATION_CHANGED", source.execute(entry, context()).reasonCode)
    }

    @Test
    fun disabledAndMissingProfilesKeepConfigurationBlockersAndLocalHardwareScope() = runBlocking {
        val targets = listOf(
            target("disabled").copy(enabled = false),
            target("missing").copy(configured = false, configurationRevision = null),
            target("local").copy(local = true)
        )
        val source = AppAuditSelectedModelProbeSource({ targets }, { error("No model execution permitted") })
        val entries = source.plan(AppAuditOptions(selectedProfileIds = listOf("disabled", "missing", "local")))
        val configurations = entries.filter { it.probeId == AppAuditSelectedModelProbeSource.CONFIGURATION }.associateBy { it.targetId }
        assertEquals(AppAuditPrerequisite.PERMISSION_REQUIRED, configurations.getValue("model:disabled").prerequisite)
        assertEquals(AppAuditPrerequisite.NOT_CONFIGURED, configurations.getValue("model:missing").prerequisite)
        assertTrue("local_inference" in configurations.getValue("model:local").sourceAreaIds)
        assertTrue(entries.filter { it.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION }.all { it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED })
    }

    private fun target(uid: String) = AppAuditSelectedModelTarget("model:$uid", true, true, "fingerprint-$uid", "a".repeat(64))
    private fun context() = AppAuditProbeContext(AppAuditLimits()) { error("An audit metadata plan must not dispatch generation") }
}
