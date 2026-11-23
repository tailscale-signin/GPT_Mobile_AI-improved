package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkContextIdentityTest {
    private val fixture = BenchmarkEngineeringTestFixtures(families = 2, repeats = 2)
    private val plan = fixture.plan.copy(
        profileUids = listOf("a"),
        profileConfigKeys = fixture.plan.profileConfigKeys.filterKeys { it == "a" },
        externalRouteDigests = fixture.plan.externalRouteDigests.filterKeys { it == "a" },
        contextExperiment = BenchmarkContextExperiment(listOf("CURRENT", "TASK")),
        contextManifestDigests = fixture.cases.associate { it.id to "a".repeat(64) },
        contextSourceIdentities = fixture.cases.associate { it.id to BenchmarkContextSourceIdentity("b".repeat(64), "c".repeat(64), 1, 3) }
    ).frozen()

    @Test fun changedRunIdentityCannotContributeMatchedPairsOrComparativeStatistics() {
        val runs = runs()
        val original = benchmarkContextComparisons(runs).single()
        assertEquals(4, original.matchedPairs)
        assertEquals(100.0, original.differencePercentagePoints)
        val candidate = runs.first { it.engineeringContextVariant == "TASK" }
        val changed = listOf(
            candidate.copy(configKey = "0".repeat(64)),
            candidate.copy(profileUid = "another-profile"),
            candidate.copy(appCommit = "another-build"),
            candidate.copy(engineeringPlan = plan.copy(cohortId = "0".repeat(64))),
            candidate.copy(engineeringPlan = plan.copy(environmentDigest = "0".repeat(64)).frozen())
        )
        for (other in changed) {
            assertIncomparable(benchmarkContextComparisons(runs.map { if (it.id == candidate.id) other else it }).single())
        }
    }

    @Test fun duplicateTrialCannotLeaveAPartialPairedDelta() {
        val runs = runs()
        assertIncomparable(benchmarkContextComparisons(runs + runs.first()).single())
    }

    private fun assertIncomparable(value: BenchmarkContextComparison) {
        assertEquals("IDENTITY_MISMATCH", value.state)
        assertEquals(0, value.matchedPairs)
        assertEquals(0, value.independentFamilies)
        assertNull(value.differencePercentagePoints)
        assertNull(value.lower95)
        assertNull(value.upper95)
        // Individual observations are still available for diagnosis.
        assertEquals(4, value.candidate.recordedTasks)
    }

    private fun runs(): List<BenchmarkRun> = benchmarkEngineeringQueueOrder(plan).map { (_, repeat, variant) ->
        val arm = requireNotNull(variant)
        fixture.runs().first { it.profileUid == "a" }.copy(
            id = "$arm-$repeat",
            engineeringPlan = plan,
            engineeringRepeatIndex = repeat,
            engineeringContextVariant = arm,
            samples = fixture.cases.map { test ->
                fixture.sample(test, arm != "CURRENT").copy(
                    contextReceipt = BenchmarkContextReceipt(
                        variant = arm, manifestDigest = "a".repeat(64), sourceSha = "b".repeat(64),
                        referenceDigest = "c".repeat(64), indexDigest = "b".repeat(64), promptDigest = "d".repeat(64),
                        selectedPathCount = 1, selectedSourceCount = 1, declaredSourceCount = 1, selectedTestCount = 0,
                        contextBytes = 100, estimatedContextTokens = 34, coldPreparationMs = 2, warmPreparationMs = 1,
                        coldSourceReads = 1, warmSourceReads = 1, warmCacheHits = 1, repeatedSourceReads = 0,
                        retrievalMode = "LEXICAL_AND_DEPENDENCY", revisionVerified = true, dispatched = true
                    )
                )
            }
        )
    }
}
