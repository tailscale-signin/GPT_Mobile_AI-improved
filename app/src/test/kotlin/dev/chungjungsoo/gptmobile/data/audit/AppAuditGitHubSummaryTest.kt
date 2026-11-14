package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditGitHubSummaryTest {
    @Test fun localPassesCannotFillAnUnmeasuredLiveScore() {
        val run = run().let { it.copy(results = listOf(result(it.plan[0], AppAuditOutcome.PASS))) }
        val github = requireNotNull(appAuditGitHubSummary(run))
        assertEquals(100.0, github.overall.localScore.score!!, 0.0)
        assertNull(github.overall.liveScore.score)
        assertEquals(1, github.overall.executed)
        assertEquals(1, github.overall.summary.verified)
        assertEquals(6, github.overall.summary.untested)
    }

    @Test fun onlyFreshEligibleLivePassesAndFailuresEnterTheRelativeScore() {
        val original = run()
        val results = listOf(
            result(original.plan[0], AppAuditOutcome.PASS),
            result(original.plan[1], AppAuditOutcome.PASS),
            result(original.plan[2], AppAuditOutcome.FAIL),
            result(original.plan[3], AppAuditOutcome.BLOCKED),
            result(original.plan[4], AppAuditOutcome.UNAVAILABLE),
            result(original.plan[5], AppAuditOutcome.PASS).let { it.copy(observation = it.observation.copy(observationSource = AppAuditObservationSource.CACHE)) }
        )
        val github = requireNotNull(appAuditGitHubSummary(original.copy(results = results)))
        assertEquals(50.0, github.overall.liveScore.score!!, 0.0)
        assertEquals(2, github.overall.liveScore.measuredWeight)
        assertEquals(1, github.overall.summary.blocked)
        assertEquals(1, github.overall.summary.unavailable)
        assertEquals(1, github.overall.summary.untested)
        assertEquals(3, github.overall.summary.exercised)
        assertEquals(6, github.overall.executed)
    }

    @Test fun changedUnconfirmedAndUnselectedResultsCannotEarnLivePoints() {
        val original = run()
        val entry = original.plan[1]
        val pass = result(entry, AppAuditOutcome.PASS)
        for (unqualified in listOf(
            pass.copy(observation = pass.observation.copy(configChanged = true)),
            pass.copy(observation = pass.observation.copy(dispatchEvidence = AppAuditDispatchEvidence.ATTEMPTED)),
            pass.copy(attempted = false)
        )) {
            assertNull(appAuditGitHubSummary(original.copy(results = listOf(unqualified)))?.overall?.liveScore?.score)
        }
        assertNull(appAuditGitHubSummary(original.copy(plan = listOf(entry.copy(selected = false)), results = listOf(pass)))?.overall?.liveScore?.score)
    }

    @Test fun aTruncatedExportCannotPublishAScoreAndHasNoRepositoryIdentifiers() {
        val original = run()
        val document = appAuditGitHubSummaryJson(original.copy(results = listOf(result(original.plan[1], AppAuditOutcome.PASS))), completeEvidence = false).jsonObject
        val summary = document.getValue("summary").jsonObject
        assertEquals(JsonNull, summary.getValue("liveReadScore").jsonObject["value"])
        assertEquals("36", document.getValue("maxGitHubRequests").jsonPrimitive.content)
        assertFalse(document.toString().contains("private-connection"))
        assertTrue(document.getValue("categories").jsonArray.isNotEmpty())
    }

    @Test fun exportedPlansRetainKnownCategoriesAndReasonsWithoutPrivateScope() = runBlocking {
        val source = AppAuditGitHubContractSource()
        val contracts = source.plan(AppAuditOptions())
        val original = run().copy(plan = contracts + run().plan.drop(1))
        val run = original.copy(results = original.plan.map { result(it, AppAuditOutcome.PASS) })
        val output = ByteArrayOutputStream()
        writeAppAuditExport(output, BenchmarkBatch(run.id, BenchmarkMode.APP_AUDIT, 1, emptyList(), audit = run))
        val text = output.toString("UTF-8")
        val document = Json.parseToJsonElement(text).jsonObject
        assertTrue(document.getValue("githubIntegration").jsonObject.getValue("categories").jsonArray.size == 8)
        assertFalse(text.contains("private-connection"))
        assertFalse(text.contains("UNKNOWN_REASON"))
        assertFalse(text.contains("unknown_probe"))
        assertFalse(text.contains("unknown_source"))
        assertTrue(document.getValue("checks").jsonArray.all { it.jsonObject.getValue("githubCategory") != JsonNull })
    }

    private fun run(): AppAuditRun {
        val checks = listOf(AppAuditGitHubCatalog.contracts.first()) + AppAuditGitHubCatalog.live
        return AppAuditRun(
            "a4772c4f-25b9-44b0-81cd-1e14cadbdb7c", 1, "c".repeat(64), "a".repeat(40), "0.9.66.7", "debug",
            "d".repeat(64), 1, 1,
            checks.mapIndexed { index, check ->
                AppAuditPlanEntry(
                    id = "check-$index",
                    sourceId = if (index == 0) AppAuditGitHubCatalog.CONTRACT_SOURCE else AppAuditGitHubCatalog.LIVE_SOURCE,
                    probeId = check.id,
                    componentId = "github",
                    label = check.label,
                    targetId = "connection:private-connection",
                    evidenceKind = if (index == 0) AppAuditEvidenceKind.LOCAL_CONTRACT else AppAuditEvidenceKind.LIVE_METADATA,
                    safetyClass = if (index == 0) AppAuditSafetyClass.LOCAL_READ else AppAuditSafetyClass.NETWORK_READ
                )
            }
        )
    }

    private fun result(entry: AppAuditPlanEntry, outcome: AppAuditOutcome) = AppAuditResult(
        entry.id,
        AppAuditObservation(
            outcome,
            if (entry.evidenceKind == AppAuditEvidenceKind.LOCAL_CONTRACT) "LOCAL_CONTRACT_PASSED" else "GITHUB_AUDIT_READ_VERIFIED",
            evidenceKind = entry.evidenceKind,
            observationSource = if (entry.evidenceKind == AppAuditEvidenceKind.LOCAL_CONTRACT) AppAuditObservationSource.FIXTURE else AppAuditObservationSource.LIVE,
            dispatchEvidence = if (entry.evidenceKind == AppAuditEvidenceKind.LOCAL_CONTRACT) AppAuditDispatchEvidence.NOT_DISPATCHED else AppAuditDispatchEvidence.CONFIRMED
        ),
        1,
        2,
        1,
        attempted = true
    )
}
