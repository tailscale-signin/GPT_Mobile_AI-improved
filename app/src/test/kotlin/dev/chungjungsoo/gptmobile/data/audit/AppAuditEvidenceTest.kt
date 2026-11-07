package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditEvidenceTest {
    @Test fun localFixturesCallProductionLogicAndNeverRequestNetworkAdmission() = runBlocking {
        val source = AppAuditLocalProbeSource()
        val entries = source.plan(AppAuditOptions())
        assertEquals(11, entries.size)
        val results = entries.map { entry ->
            val observation = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("Local fixture requested dispatch") })
            assertEquals(entry.probeId, AppAuditOutcome.PASS, observation.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, observation.observationSource)
            assertEquals(AppAuditEvidenceKind.LOCAL_CONTRACT, observation.evidenceKind)
            assertEquals(64, entry.fixtureHash?.length)
            AppAuditResult(entry.id, observation, 1, 2, 1, attempted = true)
        }
        val run = AppAuditCatalog(setOf(source)).create(id = "local", now = 1)
        val completed = run.copy(results = results)
        assertEquals(11, appAuditScore(completed).measuredWeight)
        assertEquals(100.0, appAuditScore(completed).score!!, 0.0)
        assertTrue(appAuditSummary(completed).untested > 0)
        assertFalse(completed.lineCoverageAvailable)
    }

    @Test fun cachedAndConfigurationPassesCannotInflateTheFunctionalScore() = runBlocking {
        val source = AppAuditLocalProbeSource()
        val run = AppAuditCatalog(setOf(source)).create(id = "scores", now = 1)
        val first = run.plan[0]
        fun result(source: AppAuditObservationSource, outcome: AppAuditOutcome = AppAuditOutcome.PASS) = AppAuditResult(
            first.id,
            AppAuditObservation(outcome, "FIXTURE_PASSED", evidenceKind = first.evidenceKind, observationSource = source),
            1,
            2,
            1,
            attempted = true
        )
        assertNull(appAuditScore(run.copy(results = listOf(result(AppAuditObservationSource.CACHE)))).score)
        assertNull(appAuditScore(run.copy(results = listOf(result(AppAuditObservationSource.CONFIGURATION)))).score)
        assertNull(appAuditScore(run.copy(results = listOf(result(AppAuditObservationSource.LIVE, AppAuditOutcome.UNAVAILABLE)))).score)
        val measured = run.copy(results = listOf(result(AppAuditObservationSource.FIXTURE)))
        assertEquals(1, appAuditSummary(measured).verified)
        assertEquals(run.plan.size - 1, appAuditSummary(measured).untested)
        assertEquals(1, appAuditScore(measured).passedWeight)
        assertEquals(1, appAuditScore(measured).measuredWeight)
    }

    @Test fun unknownAndDuplicateRowsDoNotExpandScoreDenominators() = runBlocking {
        val source = AppAuditLocalProbeSource()
        val run = AppAuditCatalog(setOf(source)).create(id = "duplicates", now = 1)
        val entry = run.plan.first()
        val observation = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) {})
        val result = AppAuditResult(entry.id, observation, 1, 2, 1, attempted = true)
        val changed = run.copy(results = listOf(result, result, result.copy(entryId = "unplanned")))
        assertEquals(1, appAuditScore(changed).measuredWeight)
        assertEquals(1, appAuditSummary(changed).accounted)
    }

    @Test fun sourceAreaSnapshotAndOutcomeDetailsSurviveSerialization() = runBlocking {
        val run = AppAuditCatalog(emptySet()).create(id = "persist", now = 1)
        val decoded = Json.decodeFromString<AppAuditRun>(Json.encodeToString(run))
        assertEquals(run, decoded)
        assertEquals(AppAuditSourceInventory.areas, decoded.sourceAreas)
        assertEquals(AppAuditSourceInventory.fileCount, decoded.sourceFileCount)
        assertEquals(AppAuditSourceInventory.lineCount, decoded.sourceLineCount)
        assertNull(appAuditScore(decoded).score)
    }
}
