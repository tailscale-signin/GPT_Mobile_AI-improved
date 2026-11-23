package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkEngineeringPreparationTest {
    @Test fun sharedDeadlineScalesWithDirectReadWorkAndNeverWithModelRepeats() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val direct = phoneDraft(fixture)
        assertEquals(90_000L, benchmarkEngineeringPreparationDeadlineMs(direct, 12))
        assertEquals(186_000L, benchmarkEngineeringPreparationDeadlineMs(direct, 36))
        assertEquals(442_000L, benchmarkEngineeringPreparationDeadlineMs(direct, 100))
        assertEquals(442_000L, benchmarkEngineeringPreparationDeadlineMs(direct.copy(repeats = 10).frozen(), 100))
        assertEquals(600_000L, benchmarkEngineeringPreparationDeadlineMs(direct, Int.MAX_VALUE))
        assertEquals(60_000L, benchmarkEngineeringPreparationDeadlineMs(direct, 1))
        assertEquals(60_000L, benchmarkEngineeringPreparationDeadlineMs(draft(fixture), 100))
    }

    @Test fun samePhoneAuthorityRechecksBothRoutesButFetchesImmutableDescriptorsOnlyOnce() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures(lane = BenchmarkEngineeringLane.LIVE_GITHUB)
        val opened = mutableListOf<String>()
        var manifests = 0
        val plan = phoneDraft(fixture)
        val result = benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
            opened += uid
            Route("same-authority") {
                manifests++
                count = 3 + fixture.cases.size
                manifest(fixture)
            }.apply { count = 3 }
        })
        assertEquals(plan.profileUids, opened)
        assertEquals(1, manifests)
        assertEquals(6 + fixture.cases.size, result.externalSetupRequestCount)
        assertEquals(fixture.plan.externalFixtureDigests, result.externalFixtureDigests)
    }

    @Test fun differentPhoneAuthorityAndEveryGatewayRouteReadTheirOwnManifest() = runBlocking {
        for (direct in listOf(true, false)) {
            val fixture = BenchmarkEngineeringTestFixtures(lane = if (direct) BenchmarkEngineeringLane.LIVE_GITHUB else BenchmarkEngineeringLane.EXECUTABLE_CODING)
            var manifests = 0
            val plan = if (direct) phoneDraft(fixture) else draft(fixture)
            val result = benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
                Route(if (direct) uid else "same-gateway") {
                    manifests++
                    count = 5
                    manifest(fixture)
                }
            })
            assertEquals(2, manifests)
            assertEquals(10, result.externalSetupRequestCount)
        }
    }

    @Test fun successfulSetupCountsEveryOpenedRouteAndFreezesOnlyAfterCheckpoint() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture)
        var clock = 100L
        val checkpoints = mutableListOf<BenchmarkEngineeringPlan>()
        val prepared = benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
            clock += 10
            Route(uid) {
                count = 5
                clock += 20
                manifest(fixture)
            }
        }, { checkpoints += it }, { clock })
        assertEquals(60L, prepared.externalSetupMs)
        assertEquals(10, prepared.externalSetupRequestCount)
        assertEquals(1, checkpoints.size)
        assertEquals(plan.cohortId, checkpoints.single().cohortId)
        assertTrue(checkpoints.single().externalFixtureDigests.isEmpty())
        assertEquals(prepared.externalSetupMs, checkpoints.single().externalSetupMs)
        assertTrue(prepared.matches(fixture.cases, requireExternalReceipts = true))
    }

    @Test fun failedManifestReadRetainsElapsedRequestsAndOriginalScopeInFailureBatch() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture).copy(externalSetupMs = 50, externalSetupRequestCount = 2)
        var retained = BenchmarkBatch(id = plan.id, mode = BenchmarkMode.GITHUB_INTEGRATION, createdAt = 0, items = emptyList(), engineeringPlan = plan)
        var clock = 0L
        val failure = runCatching {
            benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
                clock += 5
                Route(uid) {
                    count = 4
                    clock += 12
                    error("FIXTURE_LOOKUP_FAILED")
                }
            }, { retained = retained.withEngineeringSetupObservation(it) }, { clock })
        }.exceptionOrNull()
        assertEquals("FIXTURE_LOOKUP_FAILED", failure?.message)
        assertEquals(67L, retained.engineeringPlan?.externalSetupMs)
        assertEquals(6, retained.engineeringPlan?.externalSetupRequestCount)
        assertEquals(plan.cohortId, retained.engineeringPlan?.cohortId)
        assertTrue(retained.engineeringPlan!!.externalFixtureDigests.isEmpty())
        // Failed profile records inherit this retained plan, rather than the stale entry snapshot.
        val blockedRun = fixture.runs().first().copy(engineeringPlan = retained.engineeringPlan, state = "BLOCKED")
        assertEquals(67L, blockedRun.engineeringPlan?.externalSetupMs)
    }

    @Test fun openingFailureAfterAnotherRouteMakesFullRequestCountUnknown() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture)
        var clock = 0L
        var retained: BenchmarkEngineeringPlan? = null
        val failure = runCatching {
            benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
                clock += 8
                if (uid == plan.profileUids.last()) error("ACCOUNT_LOOKUP_FAILED_AFTER_DISPATCH")
                Route(uid) {
                    count = 7
                    clock += 9
                    manifest(fixture)
                }
            }, { retained = it }, { clock })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(25L, retained?.externalSetupMs)
        assertNull(retained?.externalSetupRequestCount)
        assertEquals(plan.cohortId, retained?.cohortId)
    }

    @Test fun cancelDuringObservableReadPersistsExactSetupBeforeCancellationCompletes() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture)
        val entered = CompletableDeferred<Unit>()
        var clock = 0L
        var retained: BenchmarkEngineeringPlan? = null
        val job = launch {
            benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid ->
                Route(uid) {
                    count = 6
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }, {
                yield()
                retained = it
            }, { clock })
        }
        entered.await()
        clock = 31
        job.cancelAndJoin()
        assertEquals(31L, retained?.externalSetupMs)
        assertEquals(6, retained?.externalSetupRequestCount)
        assertEquals(plan.cohortId, retained?.cohortId)
    }

    @Test fun cancelDuringOpenPersistsTimeWithoutInventingARequestCount() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture).copy(externalSetupMs = 20, externalSetupRequestCount = 3)
        val entered = CompletableDeferred<Unit>()
        var clock = 10L
        var retained: BenchmarkEngineeringPlan? = null
        val job = launch {
            benchmarkPrepareEngineeringPlan(plan, fixture.cases, {
                entered.complete(Unit)
                awaitCancellation()
            }, {
                yield()
                retained = it
            }, { clock })
        }
        entered.await()
        clock = 43
        job.cancelAndJoin()
        assertEquals(53L, retained?.externalSetupMs)
        assertNull(retained?.externalSetupRequestCount)
    }

    @Test fun retryDoesNotResetFailedSetupOrTurnEarlierUnknownRequestsIntoKnownTotals() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val failed = draft(fixture).copy(externalSetupMs = 40, externalSetupRequestCount = null)
        var clock = 0L
        val resumed = benchmarkPrepareEngineeringPlan(failed, fixture.cases, { uid ->
            Route(uid) {
                count = 3
                clock += 11
                manifest(fixture)
            }
        }, nowMs = { clock })
        assertEquals(62L, resumed.externalSetupMs)
        assertNull(resumed.externalSetupRequestCount)
        val known = draft(fixture).copy(externalSetupMs = 40, externalSetupRequestCount = 5)
        val resumedKnown = benchmarkPrepareEngineeringPlan(known, fixture.cases, { uid ->
            Route(uid) {
                count = 3
                clock += 11
                manifest(fixture)
            }
        }, nowMs = { clock })
        assertEquals(62L, resumedKnown.externalSetupMs)
        assertEquals(11, resumedKnown.externalSetupRequestCount)
    }

    @Test fun accountingCannotChangeScopeAndPersistenceFailurePreventsReturningAcceptedFixtures() = runBlocking {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = draft(fixture)
        val batch = BenchmarkBatch(id = plan.id, mode = BenchmarkMode.GITHUB_INTEGRATION, createdAt = 0, items = emptyList(), engineeringPlan = plan)
        try {
            batch.withEngineeringSetupObservation(plan.copy(taskPack = "repository").frozen())
            fail("Setup cannot change a frozen task pack")
        } catch (_: IllegalArgumentException) { }
        val failure = runCatching {
            benchmarkPrepareEngineeringPlan(plan, fixture.cases, { uid -> Route(uid) { manifest(fixture) } }, { error("CHECKPOINT_FAILED") })
        }.exceptionOrNull()
        assertEquals("CHECKPOINT_FAILED", failure?.message)
        assertFalse(batch.engineeringPlan!!.externalFixtureDigests.isNotEmpty())
    }

    private fun draft(fixture: BenchmarkEngineeringTestFixtures) = fixture.plan.copy(
        externalFixtureDigests = emptyMap(),
        graderDigest = null,
        externalRouteDigests = emptyMap(),
        externalAuthorityDigests = emptyMap()
    ).frozen()

    private fun phoneDraft(fixture: BenchmarkEngineeringTestFixtures) = draft(fixture).copy(
        lane = BenchmarkEngineeringLane.LIVE_GITHUB,
        externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS,
        githubConnectionUid = "explicit-connection",
        repositoryFullName = "owner/disposable-fixture",
        baseSha = "b".repeat(40)
    ).frozen()

    private fun manifest(fixture: BenchmarkEngineeringTestFixtures) = buildJsonObject {
        put("sandboxReady", true)
        put("graderDigest", requireNotNull(fixture.plan.graderDigest))
        put("environmentDigest", benchmarkHash("verifier environment"))
        put(
            "tasks",
            JsonArray(
                fixture.cases.map { test ->
                    buildJsonObject {
                        put("task_id", requireNotNull(test.engineeringTaskId))
                        put("taskDigest", requireNotNull(fixture.plan.externalFixtureDigests[test.id]))
                        put("graderDigest", requireNotNull(fixture.plan.graderDigest))
                    }
                }
            )
        )
    }

    private class Route(uid: String, val read: suspend Route.() -> JsonObject) : BenchmarkEngineeringTransport {
        override val identityDigest = benchmarkHash("route-$uid")
        var count: Int? = 2
        override val setupRequestCount: Int? get() = count
        override suspend fun request(path: String, body: JsonObject?): JsonObject = read()
    }
}
