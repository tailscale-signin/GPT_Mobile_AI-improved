package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOptions
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSelectedModelTarget
import dev.chungjungsoo.gptmobile.data.audit.appAuditModelFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAuditModelBenchmarkPreparationTest {
    @Test fun preparationRetainsTheExplicitAuditAndRefusesAnotherHistoricalRunOrProfile() = runBlocking {
        val fixture = appAuditModelFixture()
        assertEquals(fixture.binding, appAuditPrepareModelBenchmark(fixture.run, fixture.entry, fixture.profile) { fixture.target })
        assertNull(appAuditPrepareModelBenchmark(fixture.run, fixture.entry, fixture.profile.copy(uid = "replacement")) { fixture.target })
        assertNull(appAuditPrepareModelBenchmark(fixture.run.copy(plan = emptyList()), fixture.entry, fixture.profile) { fixture.target })
        assertNull(appAuditPrepareModelBenchmark(fixture.run, fixture.entry, fixture.profile) { fixture.target.copy(configurationRevision = "b".repeat(64)) })
    }
    private val saved = AppAuditSelectedModelTarget("model:accepted", configured = true, enabled = true, fingerprint = "opaque-fingerprint", configurationRevision = "a".repeat(64))

    private suspend fun entry() = AppAuditSelectedModelProbeSource({ listOf(saved) }, { saved })
        .plan(AppAuditOptions(selectedProfileIds = listOf("accepted")))
        .single { it.probeId == AppAuditSelectedModelProbeSource.GENERATION }

    @Test fun acceptedGenerationGapLooksUpOnlyItsSavedUidAndPreparesThatProfile() = runBlocking {
        val requested = mutableListOf<String>()
        val result = appAuditBenchmarkProfileUid(entry()) { uid ->
            requested += uid
            saved
        }
        assertEquals(listOf("accepted"), requested)
        assertEquals("accepted", result)
    }

    @Test fun deletedDisabledIncompleteOrReconfiguredProfilesCannotPrepareAnotherBenchmark() = runBlocking {
        val entry = entry()
        listOf(
            null,
            saved.copy(key = "model:replacement"),
            saved.copy(enabled = false),
            saved.copy(configured = false),
            saved.copy(configurationRevision = "b".repeat(64)),
            saved.copy(configurationRevision = null),
            saved.copy(fingerprint = "changed-configuration")
        ).forEach { current -> assertNull(appAuditBenchmarkProfileUid(entry) { current }) }
    }

    @Test fun metadataAndUnavailableArchivedIdentitiesNeverReachTheModelLookup() = runBlocking {
        val entry = entry()
        var lookups = 0
        listOf(
            entry.copy(targetId = "gateway:accepted"),
            entry.copy(probeId = AppAuditSelectedModelProbeSource.CONFIGURATION),
            entry.copy(evidenceKind = AppAuditEvidenceKind.LIVE_METADATA),
            entry.copy(configurationRevision = null),
            entry.copy(selected = false)
        ).forEach { invalid ->
            assertNull(
                appAuditBenchmarkProfileUid(invalid) {
                    lookups++
                    saved
                }
            )
        }
        assertEquals(0, lookups)
        assertNull(appAuditBenchmarkProfileUid(entry.copy(id = "unrelated-check")) { saved })
    }
}
