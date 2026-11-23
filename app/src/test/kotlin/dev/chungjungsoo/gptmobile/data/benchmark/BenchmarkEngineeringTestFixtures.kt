package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Independent expected outcomes for testing the coordinator and estimator, not model predictions. */
internal class BenchmarkEngineeringTestFixtures(
    val lane: BenchmarkEngineeringLane = BenchmarkEngineeringLane.EXECUTABLE_CODING,
    val families: Int = 12,
    val variants: Int = 1,
    val repeats: Int = 3,
    val policy: BenchmarkExecutionPolicy = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1
) {
    val profiles = listOf(PlatformV2(uid = "a", name = "A", model = "model-a"), PlatformV2(uid = "b", name = "B", model = "model-b"))
    val budget = BenchmarkEngineeringBudget()
    val cases = (0 until families).flatMap { family ->
        (0 until variants).map { variant ->
            val id = "family-$family-variant-$variant"
            BenchmarkCase(
                id, id, "engineering", "Repair frozen task $id", workload = lane.evidenceClass,
                engineeringLane = lane, engineeringTaskId = id, engineeringFamilyId = "family-$family", engineeringBudget = budget
            )
        }
    }
    val plan = BenchmarkEngineeringPlan(
        "experiment", lane, repeats, BenchmarkEngineeringOrdering.COUNTERBALANCED,
        profiles.map { it.uid }, profiles.associate { it.uid to benchmarkConfigKey(it, executionPolicy = policy) },
        BenchmarkEngineeringRegistry.manifestHash(cases), policy, budget, "a".repeat(40), benchmarkHash("environment"), benchmarkHash("permission"),
        externalFixtureDigests = if (external) cases.associate { it.id to benchmarkHash(it.prompt) } else emptyMap(),
        graderDigest = benchmarkHash("grader").takeIf { external },
        externalRouteDigests = if (external) profiles.associate { it.uid to benchmarkHash("route-${it.uid}") } else emptyMap()
    ).frozen()

    private val external: Boolean get() = lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)

    fun verification(test: BenchmarkCase, passed: Boolean, firstPassed: Boolean = passed, bestPassed: Boolean = passed) = BenchmarkEngineeringVerification(
        test.id, requireNotNull(plan.externalFixtureDigests[test.id]), requireNotNull(plan.graderDigest), benchmarkHash("candidate"),
        "receipt-${test.id}", benchmarkHash("evidence-${test.id}"), lane.evidenceClass,
        true, true, passed, passed, true, firstPassed, passed, 1,
        testedRevision = "b".repeat(40), publishedRevision = "b".repeat(40), remoteReadbackVerified = true,
        failureDomain = if (passed) null else BenchmarkEngineeringFailureDomain.MODEL,
        reasonCode = if (passed) null else "CHECKS_FAILED", passedChecks = if (passed) 10 else 9, totalChecks = 10,
        bestAttemptPassed = bestPassed, selectedAttemptIndex = 0, finalAttemptIndex = 0
    )

    fun sample(test: BenchmarkCase, passed: Boolean) = BenchmarkSample(
        test.id, test.label, test.category, if (passed) BenchmarkOutcome.PASSED else BenchmarkOutcome.FAILED,
        requestDispatched = true, terminalReceived = true, evidenceScope = lane.evidenceClass,
        effectiveSettingsConfirmed = true, endToEndDurationMs = 100,
        engineeringVerification = if (external) verification(test, passed) else null,
        toolAssessment = if (external) null else BenchmarkToolAssessment(passed, passed, passed, passed, passed, true, null, true, 1, 1, 0, evidenceClass = lane.evidenceClass, workflowCompleted = passed)
    )

    fun runs(outcome: (profile: Int, task: Int, repeat: Int) -> Boolean = { profile, _, _ -> profile == 0 }): List<BenchmarkRun> =
        (0 until repeats).flatMap { repeat ->
            profiles.mapIndexed { index, profile ->
                BenchmarkRun(
                    "${profile.uid}-$repeat", profile.uid, profile.name, profile.compatibleType.name, profile.model,
                    requireNotNull(plan.profileConfigKeys[profile.uid]), false, BenchmarkMode.GITHUB_INTEGRATION, 1_000L + repeat * 2 + index,
                    samples = cases.mapIndexed { task, test -> sample(test, outcome(index, task, repeat)) },
                    suiteVersion = BenchmarkSuiteRegistry.VERSION, plannedTrials = cases.size, appCommit = plan.sourceCommit,
                    batchId = plan.id, endedAt = 2_000L + repeat * 2 + index, caseManifest = cases, executionPolicy = plan.executionPolicy,
                    fixtureManifestHash = plan.fixtureManifestHash, engineeringPlan = plan, engineeringRepeatIndex = repeat
                )
            }
        }
}
