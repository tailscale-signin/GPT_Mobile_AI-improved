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
}
