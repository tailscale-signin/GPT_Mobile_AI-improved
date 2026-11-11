package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.audit.AppAuditModelConfigurationReceipt
import dev.chungjungsoo.gptmobile.data.audit.appAuditModelFixture
import dev.chungjungsoo.gptmobile.data.audit.appAuditScore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAuditQualificationEvidenceTest {
    @Test fun reportSeparatesActualAssertionsFromDeclaredModelAndNeverPromotesArchivedScore() = runBlocking {
        val fixture = appAuditModelFixture()
        val test = BenchmarkCase("ready", "Ready", "task", "Reply READY", "READY")
        val item = BenchmarkQueueItem(0, fixture.profile, "model-run", "config", state = BenchmarkQueueState.COMPLETED, auditModelBinding = fixture.binding)
        val batch = BenchmarkBatch("benchmark", BenchmarkMode.QUICK, 10, listOf(item), state = BenchmarkBatchState.COMPLETED)
        val sample = passed(test, fixture.binding.configurationRevision)
        val run = BenchmarkRun(item.runId, item.profile.uid, item.profile.name, item.profile.compatibleType.name, item.profile.model, item.configKey, false, batch.mode, 10, listOf(sample), plannedTrials = 1, appCommit = fixture.run.sourceCommit, batchId = batch.id, queueOrdinal = 0, endedAt = 101, caseManifest = listOf(test))
        val before = Json.encodeToString(fixture.run)
        val report = checkNotNull(benchmarkAuditQualificationEvidence(run, batch, item, fixture.run))
        assertEquals(JsonPrimitive("FUNCTIONAL_ASSERTIONS_PASSED"), report["functionalStatus"])
        assertEquals(JsonPrimitive("NOT_ESTABLISHED_BY_THIS_JOIN"), report["independentlyObservedModelIdentity"])
        assertEquals(JsonPrimitive(false), report["importsPromoteAuditScore"])
        assertEquals(JsonPrimitive("INDEPENDENT_IDENTITY_AND_BEHAVIOR_REVIEW_REQUIRED"), report["qualificationStatus"])
        assertEquals(before, Json.encodeToString(fixture.run))
        assertEquals(0, appAuditScore(fixture.run).passedWeight)
        assertFalse(report.toString().contains("READY"))
    }

    @Test fun missingDispatchTerminalAssertionsConfigurationOrExactFixtureNeverSatisfiesTheReport() = runBlocking {
        val fixture = appAuditModelFixture()
        val test = BenchmarkCase("ready", "Ready", "task", "Reply READY", "READY")
        val item = BenchmarkQueueItem(0, fixture.profile, "model-run", "config", state = BenchmarkQueueState.COMPLETED, auditModelBinding = fixture.binding)
        val batch = BenchmarkBatch("benchmark", BenchmarkMode.QUICK, 10, listOf(item))
        val sample = passed(test, fixture.binding.configurationRevision)
        val run = BenchmarkRun(item.runId, item.profile.uid, item.profile.name, item.profile.compatibleType.name, item.profile.model, item.configKey, false, batch.mode, 10, listOf(sample), plannedTrials = 1, appCommit = fixture.run.sourceCommit, batchId = batch.id, queueOrdinal = 0, caseManifest = listOf(test))
        listOf(
            sample.copy(requestDispatched = null), sample.copy(terminalReceived = false), sample.copy(finishReason = null),
            sample.copy(validation = null), sample.copy(auditModelConfiguration = null),
            sample.copy(auditModelConfiguration = sample.auditModelConfiguration!!.copy(afterGenerationVerified = false)),
            sample.copy(fixtureContentHash = "b".repeat(64)), sample.copy(outputCharacters = 0), sample.copy(outcome = BenchmarkOutcome.FAILED)
        ).forEach { incomplete ->
            val report = checkNotNull(benchmarkAuditQualificationEvidence(run.copy(samples = listOf(incomplete)), batch, item, fixture.run))
            assertEquals(JsonPrimitive("FUNCTIONAL_ASSERTIONS_NOT_SATISFIED"), report["functionalStatus"])
            assertEquals(JsonPrimitive(false), report["cases"]!!.jsonArray.single().jsonObject["functionalAssertionsSatisfied"])
        }
        assertEquals(JsonPrimitive("BINDING_MISMATCH_OR_UNAVAILABLE"), benchmarkAuditQualificationEvidence(run.copy(model = "another-model"), batch, item, fixture.run)!!["functionalStatus"])
        assertEquals(JsonPrimitive("EXECUTION_INCOMPLETE_OR_BLOCKED"), benchmarkAuditQualificationEvidence(run.copy(samples = emptyList()), batch, item, fixture.run)!!["functionalStatus"])
        assertEquals(JsonPrimitive("FIXTURE_MANIFEST_UNAVAILABLE_OR_INVALID"), benchmarkAuditQualificationEvidence(run.copy(samples = listOf(sample, sample)), batch, item, fixture.run)!!["functionalStatus"])
        val local = run.copy(local = true)
        val localReport = checkNotNull(benchmarkAuditQualificationEvidence(local, batch, item, fixture.run))
        assertTrue(localReport.toString().contains("NOT_CONFIRMED"))
        assertEquals(JsonPrimitive("BINDING_MISMATCH_OR_UNAVAILABLE"), localReport["functionalStatus"])
    }

    private fun passed(test: BenchmarkCase, revision: String) = BenchmarkSample(
        test.id, test.label, test.category, BenchmarkOutcome.PASSED,
        outputCharacters = 5, canonicalAnswer = "READY", answerLength = 5, firstTextMs = 2,
        requestDispatched = true, terminalReceived = true, finishReason = "stop",
        fixtureContentHash = test.contentHash, validatorVersion = test.validatorVersion,
        validation = BenchmarkValidationResult(test.validatorVersion, gradePassed = true),
        auditModelConfiguration = AppAuditModelConfigurationReceipt(revision, 1, 1, true, true, 100)
    )
}
