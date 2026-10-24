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

    @Test fun wrappedLocalRejectionsAndCooldownsAreNotMisreportedAsTransportFailures() {
        fun wrapped(message: String) = dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException(
            dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.TRANSPORT_FAILURE,
            message,
            0,
            0,
            0,
            true
        )
        assertEquals("ADMISSION", ResearchModelCoordinator.failureCategory(wrapped("RESEARCH_STAGE_TERMINAL: stage is closed")))
        assertEquals("BUDGET_DENIED", ResearchModelCoordinator.failureCategory(wrapped("RESEARCH_OUTPUT_ALLOWANCE: fixed ceiling exceeds hard limit")))
        assertEquals("RATE_LIMITED", ResearchModelCoordinator.failureCategory(wrapped("Blocked locally by saved provider cooldown. The allowance may not have reset.")))
        assertEquals("CONTEXT_LIMIT", ResearchModelCoordinator.failureCategory(wrapped("DELEGATE_OVERSIZED: prompt exceeds capacity")))
        assertEquals("REASONING_ONLY", ResearchModelCoordinator.failureCategory(wrapped("REASONING_BUDGET_EXHAUSTED: no visible answer")))
        assertEquals("EMPTY_OUTPUT", ResearchModelCoordinator.failureCategory(wrapped("NO_VISIBLE_ANSWER: provider returned no answer")))
    }

    @Test fun remoteTransportFailuresStopRepeatedSourceDispatchAndAllowPinnedHealthyPeer() = runTest {
        var attempts = 0
        val coordinator = ResearchModelCoordinator(
            listOf(
                endpoint("broken") {
                    attempts++
                    throw java.io.IOException("socket closed")
                },
                endpoint("healthy") { """{"claims":[]}""" }
            )
        )
        repeat(12) { assertNotNull(coordinator.generate(ResearchModelStage.EXTRACT, "source-$it", 512)) }
        assertEquals(2, attempts)
        assertTrue(coordinator.events.any { it.profileUid == "broken" && it.reason == "MODEL_CIRCUIT_OPEN" })
    }

    @Test fun outputLimitsDoNotOpenHealthCircuitsForTypedOrReturnedFailures() = runTest {
        for (typed in listOf(false, true)) {
            var attempts = 0
            val target = ResearchModelEndpoint("worker", "model", "route", false, { true }) { _, _, _ ->
                attempts++
                if (attempts <= 2) {
                    if (typed) {
                        throw dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException(
                            dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.OUTPUT_LIMIT,
                            "Output limit",
                            0,
                            0,
                            0,
                            true,
                            partialText = "{\"claims\":["
                        )
                    }
                    ResearchModelReply("{\"claims\":[", "route", transportComplete = true, finishReason = "length", outputLimitReached = true)
                } else {
                    ResearchModelReply("""{"claims":[]}""", "route")
                }
            }
            val coordinator = ResearchModelCoordinator(listOf(target))
            repeat(2) { assertNull(coordinator.generate(ResearchModelStage.EXTRACT, "source", 128)) }
            assertEquals(listOf("OUTPUT_LIMIT", "OUTPUT_LIMIT"), coordinator.events.map { it.failureCategory })
            assertTrue(coordinator.events.all { it.outcome == "FAILED" && !it.schemaValid && !it.stageAccepted })
            assertNotNull(coordinator.generate(ResearchModelStage.EXTRACT, "source", 512))
            assertEquals(3, attempts)
            assertFalse(coordinator.events.any { it.reason == "MODEL_CIRCUIT_OPEN" })
            assertTrue(coordinator.events.last().stageAccepted)
        }
    }

    @Test fun providerCooldownAdmissionNeverCreatesRunningGeneration() = runTest {
        var attempts = 0
        val endpoint = ResearchModelEndpoint("limited", "model", "route", false, { true }, admission = { ResearchAdmission.PROVIDER_COOLDOWN }) { _, _, _ ->
            attempts++
            ResearchModelReply(plan, "route")
        }
        val coordinator = ResearchModelCoordinator(listOf(endpoint))
        repeat(10) { assertNull(coordinator.generate(ResearchModelStage.EXTRACT, "source-$it", 512)) }
        assertEquals(0, attempts)
        assertTrue(coordinator.events.all { it.outcome == "SKIPPED" && it.reason == "PROVIDER_COOLDOWN" && it.failureCategory == "ADMISSION" })
    }

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

    @Test fun groundingRejectionRevokesStageAcceptanceWithoutRewritingSuccessfulTransport() = runTest {
        val worker = ResearchModelCoordinator(listOf(endpoint("primary") { """{"claims":[]}""" }))
        val reply = requireNotNull(worker.generate(ResearchModelStage.EXTRACT, "source", 128))
        assertTrue(worker.events.single().stageAccepted)
        worker.rejectGrounding(reply.invocationId)
        val rejected = worker.events.single()
        assertEquals("VALIDATION_FAILED", rejected.outcome)
        assertEquals("EVIDENCE_VALIDATION", rejected.failureCategory)
        assertTrue(rejected.transportComplete)
        assertTrue(rejected.schemaValid)
        assertFalse(rejected.stageAccepted)
        assertFalse(worker.completedStage("EXTRACT"))
    }

    @Test fun completedOutputLimitTransportCannotBecomeAcceptedStructuredStage() = runTest {
        val endpoint = ResearchModelEndpoint("primary", "model", "identity", false, { true }) { _, _, _ ->
            ResearchModelReply("{\"claims\":[", "identity", transportComplete = true, finishReason = "length", outputLimitReached = true)
        }
        val worker = ResearchModelCoordinator(listOf(endpoint))
        assertNull(worker.generate(ResearchModelStage.EXTRACT, "source", 128))
        val rejected = worker.events.single()
        assertEquals("OUTPUT_LIMIT", rejected.failureCategory)
        assertTrue(rejected.transportComplete)
        assertFalse(rejected.schemaValid)
        assertFalse(rejected.stageAccepted)
        assertFalse(worker.completedStage("EXTRACT"))
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

    @Test fun noticeOnlyFailuresOpenSharedCircuitAcrossStructuredStages() = runTest {
        var attempts = 0
        val worker = ResearchModelCoordinator(
            listOf(
                endpoint("primary") {
                    attempts++
                    "This model reached its response limit. Would you like me to continue?"
                }
            )
        )
        assertNull(worker.generate(ResearchModelStage.PLAN, "task", 1536))
        assertNull(worker.generate(ResearchModelStage.EXTRACT, "source", 2048))
        assertNull(worker.generate(ResearchModelStage.FIND_GAPS, "task", 1536))
        assertEquals(2, attempts)
        assertEquals("MODEL_CIRCUIT_OPEN", worker.events.last().reason)
        assertFalse(worker.completedStage("PLAN"))
    }
}
