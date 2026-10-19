package dev.chungjungsoo.gptmobile.data.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResearchModelCoordinatorTest {
    private val plan = """{"questions":[],"queries":[],"noResearchNeeded":true}"""
    private fun endpoint(id: String, key: String = id, available: suspend () -> Boolean = { true }, generate: suspend () -> String) =
        ResearchModelEndpoint(id, id, key, id == "local", available) { _, _, _ -> ResearchModelReply(generate(), key, "inv-$id") }

    @Test fun stageCompletionRetainsFailuresBeyondTheBoundedEventHistory() = kotlinx.coroutines.test.runTest {
        var ready = true
        val endpoint = ResearchModelEndpoint("primary", "model", "identity", false, { ready }) { _, _, _ -> ResearchModelReply("""{"claims":[]}""", "identity") }
        val coordinator = ResearchModelCoordinator(listOf(endpoint))
        assertNotNull(coordinator.generate(ResearchModelStage.EXTRACT, "source", 128))
        assertTrue(coordinator.completedStage("extract"))
        ready = false
        assertNull(coordinator.generate(ResearchModelStage.EXTRACT, "another source", 128))
        ready = true
        repeat(205) { coordinator.generate(ResearchModelStage.EXTRACT, "source", 128) }
        assertFalse(coordinator.completedStage("extract"))
        assertTrue(coordinator.events.size <= 200)
    }

    @Test fun localFailureDoesNotCancelPrimaryOrDiscardSuccessfulOutput() = runTest {
        var fallback = 0
        val worker = ResearchModelCoordinator(
            listOf(
                endpoint("local") { error("load failed") },
                endpoint("primary") {
                    fallback++
                    plan
                }
            )
        )
        assertEquals(plan, worker.generate(ResearchModelStage.PLAN, "task", 128)?.text)
        assertEquals(1, fallback)
        assertEquals(listOf("FAILED", "SUCCESS"), worker.events.map { it.outcome })
    }

    @Test fun timeoutUsesNextPinnedEndpointAndStopsRepeatingFailedModel() = runTest {
        var localAttempts = 0
        val worker = ResearchModelCoordinator(
            listOf(
                endpoint("local") {
                    localAttempts++
                    delay(100)
                    plan
                },
                endpoint("primary") { plan }
            ),
            deadlineMillis = 10
        )
        repeat(3) { assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128)) }
        assertEquals(2, localAttempts)
        assertTrue(worker.events.any { it.reason == "MODEL_CIRCUIT_OPEN" })
    }

    @Test fun unavailableAndMalformedResponsesAreNotSuccessfulReview() = runTest {
        val worker = ResearchModelCoordinator(listOf(endpoint("unavailable", available = { false }) { error("must not dispatch") }, endpoint("malformed") { "plain text, not a review" }))
        assertNull(worker.generate(ResearchModelStage.REVIEW, "evidence", 128, "author"))
        assertEquals(listOf("SKIPPED", "INVALID_RESPONSE"), worker.events.map { it.outcome })
    }

    @Test fun readinessExceptionFallsBackAndNoCandidateIsNotAnInvocation() = runTest {
        val worker = ResearchModelCoordinator(listOf(endpoint("broken", available = { error("readiness unavailable") }) { plan }, endpoint("healthy") { plan }))
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        val absent = ResearchModelCoordinator(emptyList())
        assertFalse(absent.hasCandidates)
        assertNull(absent.generate(ResearchModelStage.PLAN, "task", 128))
        assertTrue(absent.events.isEmpty())
    }

    @Test fun parentCancellationIsNotRetriedAsProviderFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        var fallback = 0
        val worker = ResearchModelCoordinator(
            listOf(
                endpoint("local") {
                    started.complete(Unit)
                    delay(10_000)
                    plan
                },
                endpoint("primary") {
                    fallback++
                    plan
                }
            )
        )
        val request = async { worker.generate(ResearchModelStage.PLAN, "task", 128) }
        runCurrent()
        started.await()
        request.cancelAndJoin()
        assertEquals(0, fallback)
        assertEquals("CANCELLED", worker.events.last().outcome)
    }

    @Test fun revocationDuringGenerationDropsResultAndDoesNotTryAnotherDestination() = runTest {
        var enabled = true
        var fallback = 0
        val worker = ResearchModelCoordinator(
            listOf(
                endpoint("local") {
                    enabled = false
                    plan
                },
                endpoint("primary") {
                    fallback++
                    plan
                }
            ),
            stillEnabled = { enabled }
        )
        assertNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals(0, fallback)
        assertEquals("PERMISSION_REVOKED", worker.events.last().reason)
    }

    @Test fun onlyDistinctKnownModelsCanClaimIndependentReview() = runTest {
        val review = """{"verdict":"Supported","relevance":"Relevant","questionId":"Q1"}"""
        for ((reviewer, author, expected) in listOf(Triple("known-B", "known-A", true), Triple("known-A", "known-A", false), Triple("", "known-A", false), Triple("known-B", "", false))) {
            val worker = ResearchModelCoordinator(emptyList(), endpoint("reviewer", reviewer) { review })
            assertEquals(expected, worker.generate(ResearchModelStage.REVIEW, "quoted evidence", 128, author)?.independent)
        }
    }

    @Test fun persistedTimelineDoesNotExposePromptOrModelOutput() = runTest {
        val worker = ResearchModelCoordinator(listOf(endpoint("primary") { plan }))
        worker.generate(ResearchModelStage.PLAN, "PRIVATE_SOURCE_SENTINEL", 128)
        val snapshot = ResearchSnapshot("task", modelEvents = worker.events)
        val restored = ResearchSnapshot.parse(snapshot.json())!!
        assertEquals(worker.events, restored.modelEvents)
        assertFalse(snapshot.json().toString().contains("PRIVATE_SOURCE_SENTINEL"))
        assertTrue(restored.modelEvents.single().inputTokensEstimate > 0)
    }

    @Test fun failedPlanningDoesNotOpenTheExtractionOrReviewCircuit() = runTest {
        val endpoint = ResearchModelEndpoint("worker", "model", "same-model", false, { true }) { stage, _, _ ->
            if (stage == ResearchModelStage.PLAN) error("planner schema failed")
            ResearchModelReply(if (stage == ResearchModelStage.EXTRACT) """{"claims":[]}""" else """{"verdict":"Insufficient","relevance":"Relevant","questionId":"Q1"}""", "same-model")
        }
        val coordinator = ResearchModelCoordinator(listOf(endpoint))
        repeat(2) { assertNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128)) }
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128))
        assertNotNull(coordinator.generate(ResearchModelStage.EXTRACT, "source", 128))
        assertNotNull(coordinator.generate(ResearchModelStage.REVIEW, "claim", 128))
        assertEquals(listOf("PLAN"), coordinator.events.filter { it.reason == "MODEL_CIRCUIT_OPEN" }.map { it.stage })
    }

    @Test fun failedStageCanRecoverAfterBoundedCooldown() = runTest {
        var now = 0L
        var healthy = false
        var attempts = 0
        val coordinator = ResearchModelCoordinator(
            listOf(
                endpoint("worker") {
                    attempts++
                    if (!healthy) error("provider unavailable")
                    plan
                }
            ),
            monotonicClock = { now }
        )
        repeat(3) { assertNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128)) }
        assertEquals(2, attempts)
        now = 30_001
        healthy = true
        assertNotNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals(3, attempts)
    }

    @Test fun failureBeforeInvocationRecordsSanitizedOrigin() = runTest {
        val coordinator = ResearchModelCoordinator(listOf(endpoint("worker", available = { throw IllegalArgumentException("PRIVATE_SOURCE_SENTINEL") }) { plan }))
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128))
        val event = coordinator.events.single()
        assertEquals("READINESS", event.failurePhase)
        assertEquals("ARGUMENT_VALIDATION", event.failureCategory)
        assertEquals("IllegalArgumentException", event.exceptionType)
        assertNull(event.invocationId)
        assertFalse(event.toString().contains("PRIVATE_SOURCE_SENTINEL"))
    }

    @Test fun onlyOneHalfOpenProbeRunsAfterCircuitCooldown() = runTest {
        var now = 0L
        var attempts = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = ResearchModelCoordinator(
            listOf(
                endpoint("worker") {
                    attempts++
                    if (attempts <= 2) error("provider failed")
                    started.complete(Unit)
                    release.await()
                    plan
                }
            ),
            monotonicClock = { now }
        )
        repeat(2) { assertNull(coordinator.generate(ResearchModelStage.PLAN, "task", 128)) }
        now = 30_001
        val probe = async { coordinator.generate(ResearchModelStage.PLAN, "task", 128) }
        started.await()
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "another task", 128))
        assertEquals(3, attempts)
        release.complete(Unit)
        assertNotNull(probe.await())
    }
}
