package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAuditModelCompatibilityTest {
    @Test
    fun legacyAttemptedFlagNeverInventsAnAdmissionReceipt() {
        val original = result("legacy", AppAuditObservationSource.NONE, AppAuditDispatchEvidence.NOT_DISPATCHED)
        val legacy = JsonObject(Json.parseToJsonElement(Json.encodeToString(original)).jsonObject - "networkAdmission")
        val decoded = Json.decodeFromString<AppAuditResult>(legacy.toString())
        assertEquals(true, decoded.attempted)
        assertEquals(AppAuditNetworkAdmission.UNKNOWN, decoded.networkAdmission)
        val run = run(listOf(entry("legacy"))).copy(results = listOf(decoded))
        assertEquals(1, run.networkProbesStarted)
        assertEquals(1, run.networkProbesAdmissionUnknown)
        assertEquals(0, run.networkProbesNotAdmitted)
        assertEquals(0, run.networkAttemptBudgetUsed)
    }

    @Test
    fun legacyNetworkReservationsConsumeBudgetWithoutInventingConfirmedDispatch() {
        val original = run(listOf(entry("pending"))).copy(networkProbesDispatched = 3)
        val legacy = JsonObject(Json.parseToJsonElement(Json.encodeToString(original)).jsonObject - "networkProbesAttempted")
        val decoded = Json.decodeFromString<AppAuditRun>(legacy.toString())
        assertEquals(3, decoded.networkProbesAttempted)
        assertEquals(3, decoded.networkAttemptBudgetUsed)
        assertEquals(0, decoded.networkProbesConfirmed)
        assertEquals(0, decoded.networkProbesDispatchUnknown)
        assertNull(appAuditScore(decoded).score)
    }

    @Test
    fun countersSeparateLocalRejectionCachedHistoryConfirmedAndUncertainAttempts() {
        val entries = listOf("local", "confirmed", "unknown", "cached", "active").map(::entry)
        val run = run(entries).copy(
            networkProbesAttempted = 4,
            networkProbesDispatched = 4,
            activeProbeId = "active",
            activeDispatchEvidence = AppAuditDispatchEvidence.ATTEMPTED,
            results = listOf(
                result("local", AppAuditObservationSource.NONE, AppAuditDispatchEvidence.NOT_DISPATCHED),
                result("confirmed", AppAuditObservationSource.LIVE, AppAuditDispatchEvidence.CONFIRMED),
                result("unknown", AppAuditObservationSource.LIVE, AppAuditDispatchEvidence.UNKNOWN),
                result("cached", AppAuditObservationSource.CACHE, AppAuditDispatchEvidence.CONFIRMED)
            )
        )
        val decoded = Json.decodeFromString<AppAuditRun>(Json.encodeToString(run))
        assertEquals(4, decoded.networkAttemptBudgetUsed)
        assertEquals(1, decoded.networkProbesConfirmed)
        assertEquals(2, decoded.networkProbesDispatchUnknown)
        val recovered = decoded.interrupted(20, "PROCESS_INTERRUPTION")
        assertEquals(4, recovered.networkAttemptBudgetUsed)
        assertEquals(1, recovered.networkProbesConfirmed)
        assertEquals(2, recovered.networkProbesDispatchUnknown)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, recovered.results.last().dispatchEvidence)
    }

    @Test
    fun changedConfigurationAndUnconfirmedNetworkPassesCannotEarnVerifiedWeight() {
        val entry = entry("live")
        val observation = AppAuditObservation(
            AppAuditOutcome.PASS,
            "MCP_DISCOVERY_VERIFIED",
            evidenceKind = entry.evidenceKind,
            observationSource = AppAuditObservationSource.LIVE,
            dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED
        )
        fun score(value: AppAuditObservation) = appAuditScore(run(listOf(entry)).copy(results = listOf(AppAuditResult(entry.id, value, 1, 2, 1, true)))).score
        assertEquals(100.0, score(observation)!!, 0.0)
        assertNull(score(observation.copy(configChanged = true)))
        assertNull(score(observation.copy(dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN)))
        assertNull(score(observation.copy(dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED)))
        assertNull(score(observation.copy(observationSource = AppAuditObservationSource.FIXTURE)))
    }

    @Test
    fun externalToolPlaceholdersCannotAcquireScoreFromAnUnsupportedImportedPass() {
        val entry = entry("tool").copy(evidenceKind = AppAuditEvidenceKind.TOOL_EXECUTION, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY, prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED)
        val observation = AppAuditObservation(AppAuditOutcome.PASS, "FIXTURE_PASSED", evidenceKind = entry.evidenceKind, observationSource = AppAuditObservationSource.LIVE, dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED)
        val scored = run(listOf(entry)).copy(results = listOf(AppAuditResult(entry.id, observation, 1, 2, 1, true)))
        assertNull(appAuditScore(scored).score)
        assertEquals(0, appAuditSummary(scored).verified)
    }

    private fun entry(id: String) = AppAuditPlanEntry(id, "plugins", "mcp_discovery", "mcp_connection", "MCP discovery", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, safetyClass = AppAuditSafetyClass.NETWORK_READ)
    private fun result(id: String, source: AppAuditObservationSource, dispatch: AppAuditDispatchEvidence) = AppAuditResult(id, AppAuditObservation(AppAuditOutcome.FAIL, "MCP_DISCOVERY_UNAVAILABLE", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = source, dispatchEvidence = dispatch), 1, 2, 1, true)
    private fun run(plan: List<AppAuditPlanEntry>) = AppAuditRun("compatibility", 1, "a".repeat(64), "b".repeat(40), "0.9.66.6", "debug", "c".repeat(64), 1, 1, plan)
}
