package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEngineeringComparisonTest {
    @Test fun verifiedPairedTasksCanEstablishASuiteScopedWinner() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val comparison = benchmarkEngineeringComparison(fixture.runs())
        assertEquals(BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE, comparison.state)
        assertEquals("a", comparison.winnerUid)
        assertEquals(listOf(36, 0), comparison.resolvedCounts)
        assertEquals(listOf(36, 36), comparison.eligibleCounts)
        assertEquals(36, comparison.matchedTasks)
        assertEquals(12, comparison.uniqueFamilies)
        assertTrue(comparison.lower95!! > 0.0)
        assertTrue(comparison.lower95 < 100.0)
        assertEquals(100.0, comparison.upper95!!, 0.0)
        assertTrue(comparison.reason.contains("this lane, suite"))
    }

    @Test fun identicalScoresAndMixedEvidenceRemainInconclusive() {
        val fixture = BenchmarkEngineeringTestFixtures()
        for (runs in listOf(fixture.runs { _, _, _ -> true }, fixture.runs { profile, task, _ -> task % 3 == 0 || task % 3 == profile + 1 })) {
            val comparison = benchmarkEngineeringComparison(runs)
            assertEquals(BenchmarkEngineeringComparisonState.INCONCLUSIVE, comparison.state)
            assertNull(comparison.winnerUid)
            assertEquals(0.0, comparison.deltaPercentagePoints!!, 0.0)
        }
    }

    @Test fun variantsAndRepeatsDoNotInflateIndependentFamilyCount() {
        val fixture = BenchmarkEngineeringTestFixtures(families = 2, variants = 20, repeats = 10)
        val comparison = benchmarkEngineeringComparison(fixture.runs())
        assertEquals(400, comparison.matchedTasks)
        assertEquals(2, comparison.uniqueFamilies)
        assertEquals(BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE, comparison.state)
        assertNull(comparison.winnerUid)
        assertEquals(.5, exactPairedSignProbability(2, 0), 0.0)
    }

    @Test fun oneRepeatCannotProduceAnEstablishedWinner() {
        val comparison = benchmarkEngineeringComparison(BenchmarkEngineeringTestFixtures(repeats = 1).runs())
        assertEquals(BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE, comparison.state)
        assertNull(comparison.winnerUid)
    }

    @Test fun unsupportedAndInfrastructureEvidenceBlockWinnerWithoutBecomingModelZeros() {
        val fixture = BenchmarkEngineeringTestFixtures()
        for (domain in listOf(BenchmarkEngineeringFailureDomain.UNSUPPORTED, BenchmarkEngineeringFailureDomain.INFRASTRUCTURE, BenchmarkEngineeringFailureDomain.HARNESS)) {
            val runs = fixture.runs().toMutableList()
            val original = runs[1].samples.first()
            val unavailable = original.copy(outcome = BenchmarkOutcome.ERROR, engineeringVerification = original.engineeringVerification!!.copy(failureDomain = domain))
            runs[1] = runs[1].copy(samples = listOf(unavailable) + runs[1].samples.drop(1))
            val comparison = benchmarkEngineeringComparison(runs)
            assertEquals(BenchmarkEngineeringComparisonState.INCOMPLETE, comparison.state)
            assertEquals(35, comparison.matchedTasks)
            assertEquals(listOf(36, 35), comparison.eligibleCounts)
            assertEquals(1, comparison.excludedPairs[domain.name])
            assertNull(comparison.winnerUid)
        }
    }

    @Test fun trustedModelRejectionCountsAsFailureEvenWhenInvalidPatchCannotRun() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val test = fixture.cases.first()
        val rejected = fixture.sample(test, false).copy(
            engineeringVerification = fixture.verification(test, false).copy(
                independentChecksExecuted = false,
                requiredChecksKnown = false,
                hiddenChecksPassed = null,
                regressionPassed = null,
                totalChecks = null,
                passedChecks = null,
                reasonCode = "INVALID_PATCH"
            )
        )
        assertEquals(false, benchmarkEngineeringTaskResult(fixture.plan, test, rejected))
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, rejected.copy(engineeringVerification = null)))
    }

    @Test fun alteredOrDuplicateTrialIdentityCannotEstablishWinner() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val original = fixture.runs()
        val changed = original.first().copy(appCommit = "c".repeat(40))
        for (runs in listOf(listOf(changed) + original.drop(1), original + original.first(), listOf(original.first().copy(configKey = benchmarkHash("other settings"))) + original.drop(1))) {
            val result = benchmarkEngineeringComparison(runs)
            assertEquals(BenchmarkEngineeringComparisonState.INCOMPARABLE, result.state)
            assertNull(result.winnerUid)
        }
    }

    @Test fun bestAttemptNeverReplacesTheFinalDeliveredArtifact() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val runs = fixture.runs { _, _, _ -> false }.map { run ->
            run.copy(samples = run.samples.map { it.copy(engineeringVerification = it.engineeringVerification!!.copy(bestAttemptPassed = true, firstAttemptPassed = true)) })
        }
        val comparison = benchmarkEngineeringComparison(runs)
        assertEquals(listOf(0, 0), comparison.resolvedCounts)
        assertEquals(listOf(36, 36), comparison.bestAttemptCounts)
        assertEquals(listOf(36, 36), comparison.firstAttemptCounts)
        assertEquals(listOf(0, 0), comparison.finalArtifactCounts)
        assertNull(comparison.winnerUid)
    }

    @Test fun passingProseAndWrongFixtureReceiptDoNotQualify() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val test = fixture.cases.first()
        val sample = fixture.sample(test, true)
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, sample.copy(engineeringVerification = null, finalAnswer = "All tests pass")))
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, sample.copy(engineeringVerification = sample.engineeringVerification!!.copy(fixtureDigest = benchmarkHash("different fixture")))))
        assertFalse(sample.engineeringVerification!!.copy(totalChecks = 0, passedChecks = 0).taskComplete(fixture.plan, test))
    }

    @Test fun liveReadbackMustMatchTheExactTestedRevision() {
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB)
        val test = fixture.cases.first()
        val receipt = fixture.verification(test, true)
        assertTrue(receipt.taskComplete(fixture.plan, test))
        assertFalse(receipt.copy(publishedRevision = "c".repeat(40)).taskComplete(fixture.plan, test))
        assertFalse(receipt.copy(remoteReadbackVerified = null).taskComplete(fixture.plan, test))
    }

    @Test fun localGenerationContractCanEstablishOnlyAnExplicitFailureWithoutGraderClaims() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val test = fixture.cases.first()
        val local = fixture.verification(test, false).copy(
            verificationScope = "LOCAL_GENERATION_CONTRACT",
            independentChecksExecuted = false, requiredChecksKnown = false,
            hiddenChecksPassed = null, regressionPassed = null, integrityPassed = null,
            testedRevision = null, publishedRevision = null, remoteReadbackVerified = null,
            passedChecks = null, totalChecks = null
        )
        assertTrue(local.boundTo(fixture.plan, test))
        assertFalse(local.taskComplete(fixture.plan, test))
        assertEquals(false, benchmarkEngineeringTaskResult(fixture.plan, test, fixture.sample(test, false).copy(engineeringVerification = local)))
        assertFalse(local.copy(finalArtifactPassed = true).boundTo(fixture.plan, test))
        assertFalse(local.copy(independentChecksExecuted = true).boundTo(fixture.plan, test))
        assertFalse(local.copy(remoteReadbackVerified = true).boundTo(fixture.plan, test))
        assertFalse(local.copy(verificationScope = "MODEL_SAID_SO").boundTo(fixture.plan, test))
    }

    @Test fun strictSettingsMustBeConfirmedButSavedProfileComparisonStaysExplicit() {
        val fixture = BenchmarkEngineeringTestFixtures(policy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1)
        val runs = fixture.runs().map { it.copy(samples = it.samples.map { sample -> sample.copy(effectiveSettingsConfirmed = false) }) }
        val result = benchmarkEngineeringComparison(runs)
        assertEquals(BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE, result.state)
        assertFalse(result.settingsVerified)
        assertNull(result.winnerUid)
    }

    @Test fun unanimousWinsRetainFiniteSampleUncertainty() {
        val unanimous = pairedFamilyInterval(List(12) { 1.0 })!!
        assertTrue(unanimous.first in 0.21..0.22)
        assertEquals(1.0, unanimous.second, 0.0)
        val values = listOf(-1.0, 1.0, 0.0, .5, 1.0, 1.0)
        assertEquals(pairedFamilyInterval(values), pairedFamilyInterval(values))
        assertNull(pairedFamilyInterval(listOf(1.0)))
        assertNull(pairedFamilyInterval(listOf(Double.NaN, 1.0)))
    }

    @Test fun observedSetupTotalsCanAccumulateWithoutChangingTheFrozenExperiment() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val runs = fixture.runs().mapIndexed { index, run -> run.copy(engineeringPlan = fixture.plan.copy(externalSetupMs = 20L * index, externalSetupRequestCount = 2 * index)) }
        val result = benchmarkEngineeringComparison(runs)
        assertEquals(BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE, result.state)
        assertEquals(fixture.plan.cohortId, result.cohortId)
    }
}
