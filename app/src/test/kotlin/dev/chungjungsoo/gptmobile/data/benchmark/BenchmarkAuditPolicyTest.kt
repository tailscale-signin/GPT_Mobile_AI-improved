package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAuditPolicyTest {
    private val capability = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
    private val savedProfile = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1
    private val profile = PlatformV2(uid = "profile", name = "Profile", model = "model", temperature = .7f, maxTokens = 111, systemPrompt = "Saved system instructions", reasoning = true, stream = false, maxToolCalls = 2)

    @Test fun capabilityAndSavedProfileKeepSeparateSettingsEnrollmentAndScorePopulations() {
        val capabilityProfile = benchmarkProfile(profile, true, "tools", capability)
        val actual = benchmarkProfile(profile, true, "tools", savedProfile)
        assertEquals(0f, capabilityProfile.temperature)
        assertEquals(profile.copy(batchMode = false), actual)
        assertEquals(.7f, actual.temperature)
        val enrollment = benchmarkEnrollment(listOf(profile), "environment")
        val configCapability = benchmarkConfigKey(profile, "environment", capability)
        val configSaved = benchmarkConfigKey(profile, "environment", savedProfile)
        assertNotEquals(configCapability, configSaved)
        assertEquals(configCapability, enrollment.getValue(benchmarkEnrollmentKey(profile.uid, capability)))
        assertEquals(configSaved, enrollment.getValue(benchmarkEnrollmentKey(profile.uid, savedProfile)))
        val test = BenchmarkCase("task", "Task", "task", "Reply OK", "OK")
        val sample = BenchmarkSample("task", "Task", "task", BenchmarkOutcome.PASSED, canonicalAnswer = "OK", outputCharacters = 2, requestDispatched = true)
        val first = BenchmarkRun("cap", profile.uid, "Profile", "CUSTOM", "model", configCapability, false, BenchmarkMode.QUICK, 1, suiteVersion = 10, measurementVersion = 4, executionPolicy = capability, caseManifest = listOf(test), samples = listOf(sample), plannedTrials = 1)
        val second = first.copy(id = "saved", configKey = configSaved, executionPolicy = savedProfile, samples = listOf(sample.copy(outcome = BenchmarkOutcome.FAILED)))
        val snapshot = DynamicScoreEngine.snapshot(listOf(first, second), 1, 2, enrollment)
        assertEquals(2, snapshot.rows.size)
        assertEquals(setOf(capability, savedProfile), snapshot.rows.map { it.executionPolicy }.toSet())
        assertTrue(snapshot.rows.all { it.sourceRunIds.size == 1 })
        assertNotEquals(DynamicScoreEngine.cohort(first), DynamicScoreEngine.cohort(second))
        assertTrue(benchmarkRunSelection(listOf(first, second), snapshot, enrollment).all { "INACTIVE_OR_UNENROLLED_REVISION" !in it.exclusionReasons })
    }

    @Test fun savedPolicyIdentityIncludesToolAndSafetySettingsButExcludesNamesAndSecrets() {
        val first = benchmarkConfigKey(profile, executionPolicy = savedProfile)
        assertNotEquals(first, benchmarkConfigKey(profile.copy(maxToolCalls = 3), executionPolicy = savedProfile))
        assertNotEquals(first, benchmarkConfigKey(profile.copy(disableRemoteTools = true), executionPolicy = savedProfile))
        assertNotEquals(first, benchmarkConfigKey(profile.copy(dangerousContentSafetyThreshold = "BLOCK_LOW_AND_ABOVE"), executionPolicy = savedProfile))
        assertEquals(first, benchmarkConfigKey(profile.copy(name = "Renamed", uid = "different", id = 77, token = "new-key", secretRef = "new-secret-reference"), executionPolicy = savedProfile))
    }

    @Test fun appInstrumentationAndStructuredSafetyAreNotNativeAcknowledgements() {
        val local = profile.copy(compatibleType = ClientType.LITERT_LM)
        val test = BenchmarkIntelligenceFixtures.batches(0).first()
        val sample = BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED)
        val receipt = benchmarkSettingsReceipt(local, test, savedProfile, sample, BenchmarkRequestContext(savedProfile, test.responseSchemaJson))
        assertEquals(true, receipt.profile.reasoning)
        assertEquals(false, receipt.requested.reasoning)
        assertEquals(.7f, receipt.requested.temperature)
        assertEquals(111, receipt.requested.outputTokens)
        assertEquals("false", receipt.appAppliedSafetyOverrides["thinking"])
        assertEquals("false", receipt.appAppliedSafetyOverrides["speculativeDecoding"])
        assertEquals("true", receipt.appAppliedInstrumentationOverrides["nativeMetricsEnabled"])
        assertTrue(receipt.acknowledgedFields.isEmpty())
        assertTrue(receipt.acknowledgedGenerationIds.isEmpty())
    }

    @Test fun savedBatchModeRemainsUnchangedAndItsPerCaseOverrideIsExplicit() {
        val saved = profile.copy(batchMode = true)
        val test = BenchmarkCase("task", "Task", "task", "Reply OK", "OK")
        val sample = BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED)
        val receipt = benchmarkSettingsReceipt(saved, test, savedProfile, sample)
        assertTrue(saved.batchMode)
        assertEquals(true, receipt.profile.batchMode)
        assertEquals(false, receipt.requested.batchMode)
        assertTrue("batchMode" in receipt.overriddenFields)
        assertEquals("false", receipt.appAppliedInstrumentationOverrides["batchMode"])
        assertTrue(receipt.acknowledgedFields.isEmpty())
        assertTrue(receipt.acknowledgedGenerationIds.isEmpty())
        val unchanged = benchmarkSettingsReceipt(saved.copy(batchMode = false), test, savedProfile, sample)
        assertFalse("batchMode" in unchanged.overriddenFields)
        assertFalse("batchMode" in unchanged.appAppliedInstrumentationOverrides)
    }

    @Test fun versionedWorkloadDeadlinesKeepTheHistoricalNinetySecondContract() {
        val full = benchmarkSuiteForVersion(10, BenchmarkMode.FULL)
        val tool = full.first { it.category == "tools" }
        val intelligence = full.first { it.category == IntelligenceBenchmark.CATEGORY }
        val long = full.first { it.category == "speed" && it.workload == "long" }
        assertEquals(90_000L, benchmarkCaseDeadlineMs(BenchmarkMode.FULL, tool, 9))
        assertEquals(300_000L, benchmarkCaseDeadlineMs(BenchmarkMode.FULL, tool, 10))
        assertEquals(180_000L, benchmarkCaseDeadlineMs(BenchmarkMode.FULL, intelligence, 10))
        assertEquals(360_000L, benchmarkCaseDeadlineMs(BenchmarkMode.FULL, long, 10))
        assertEquals(90_000L, benchmarkCaseDeadlineMs(BenchmarkMode.QUICK, intelligence, 10))
        val sustained = BenchmarkPerformanceSuite.cases().first { it.performanceTrial?.dimension == BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT }
        assertEquals(600_000L, benchmarkCaseDeadlineMs(BenchmarkMode.PERFORMANCE, sustained, 10))
        val receipt = benchmarkDeadlineReceipt(BenchmarkMode.FULL, tool, 10)
        assertEquals(300_000L, receipt.totalRequestMs)
        assertEquals("benchmark-workload-deadlines-v2", receipt.policy)
    }

    @Test fun portableScoreboardRetainsNewFixtureProvenanceAndRejectsUnknownOrSavedPolicy() {
        val plan = benchmarkSuiteForVersion(10, BenchmarkMode.QUICK, 2)
        val samples = plan.map { test ->
            val answer = test.expected ?: "complete"
            BenchmarkSample(
                test.id, test.label, test.category, BenchmarkOutcome.PASSED, durationMs = 500, outputCharacters = answer.length, canonicalAnswer = answer, workload = test.workload,
                intelligenceAnswers = if (test.category == IntelligenceBenchmark.CATEGORY) IntelligenceBenchmark.evaluateBatch(test, answer, 4).answers else emptyMap(),
                intelligenceSemantics = benchmarkIntelligenceEvidence(test, answer, true), intelligenceParseable = true.takeIf { test.category == IntelligenceBenchmark.CATEGORY }
            )
        }
        val run = BenchmarkRun("current", profile.uid, "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.QUICK, 1, suiteVersion = 10, executionPolicy = capability, samples = samples, plannedTrials = 5, caseManifest = plan, fixtureRotationSeed = 2, fixtureSetVersion = 4, fixtureManifestHash = benchmarkHash(plan.joinToString("\n") { it.id + it.prompt + it.expected }))
        val submission = checkNotNull(BenchmarkScoreboard.fromRun(run))
        assertEquals(3, submission.schemaVersion)
        assertEquals(2L, submission.fixtureRotationSeed)
        assertEquals(8, submission.trials.single { it.id == "intelligence-batch-0" }.intelligence.size)
        assertNotNull(BenchmarkScoreboard.evaluate(submission))
        assertNull(BenchmarkScoreboard.fromRun(run.copy(executionPolicy = savedProfile)))
        assertNull(BenchmarkScoreboard.fromRun(run.copy(executionPolicy = null)))
        assertNull(BenchmarkScoreboard.evaluate(submission.copy(executionPolicy = savedProfile)))
        assertNull(BenchmarkScoreboard.evaluate(submission.copy(fixtureManifestHash = "0".repeat(64))))
        assertFalse(benchmarkScoreboardIneligibility(run.copy(executionPolicy = null)).isNullOrBlank())
    }
}
