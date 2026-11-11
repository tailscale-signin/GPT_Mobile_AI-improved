package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProgressNarratorTest {
    private fun state(revision: Long = 1) = GitHubProgressSnapshot(revision, "Inspect workflow failures", 2, 1, "Reading build logs", listOf("Job 7 returned a failed result.", "Read the workflow definition."), "Inspect the failed step")
    private val prose = "The workflow definition and the result from job 7 are available. An operation needs recovery, so the next step is to inspect its failing output and preserve the findings already collected."

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            events.forEach { emit(it) }
        }
    }

    @Test fun onlySelectedSessionNarratesNewEvidenceAtTheAdmittedCadence() = runTest {
        var clock = 0L
        var revision = 1L
        var newObservation = false
        var calls = 0
        val summaries = mutableListOf<ApiState.ActivitySummary>()
        val narrator = GitHubProgressNarrator(
            snapshot = { state(revision).let { if (newObservation) it.copy(recentSteps = it.recentSteps + "Job 7 diagnostic is available.") else it } },
            openSelected = { prompt, cap ->
                calls++
                assertTrue(prompt.contains("Job 7"))
                assertEquals(192, cap)
                session(ProviderEvent.TextDelta(prose), ProviderEvent.Completed)
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { summaries += it },
            now = { clock },
            deterministic = false
        )
        clock = 44_999
        narrator.beforeRound()
        assertEquals(0, calls)
        clock = 45_000
        narrator.beforeRound()
        assertEquals(1, calls)
        assertTrue(summaries.single().modelAuthored)
        clock = 90_000
        narrator.beforeRound()
        assertEquals(1, calls)
        revision++
        narrator.beforeRound()
        assertEquals("A revision or counter change alone is not new narrative evidence", 1, calls)
        newObservation = true
        revision++
        narrator.beforeRound()
        assertEquals(2, calls)
        assertEquals("Identical prose does not create another summary", 1, summaries.size)
    }

    @Test fun optionalGenerationFailureNeverRetriesOrBlocksRepositoryWork() = runTest {
        var clock = 0L
        var revision = 1L
        var calls = 0
        val narrator = GitHubProgressNarrator(
            snapshot = { state(revision) },
            openSelected = { _, _ ->
                calls++
                session(ProviderEvent.Failed("Backend exhausted the output allowance", errorCode = "completion_repair_budget_exhausted"))
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { error("Failed generation cannot become authored progress") },
            now = { clock },
            deterministic = false
        )
        val work = session(ProviderEvent.TextDelta("Repository result"), ProviderEvent.Completed)
        val wrapped = GitHubProgressSession(work, beforeRound = { narrator.beforeRound() }) {}
        clock = 45_000
        assertEquals(listOf(ProviderEvent.TextDelta("Repository result"), ProviderEvent.Completed), wrapped.streamRound(emptyList(), emptyList()).toList())
        clock = 90_000
        wrapped.streamRound(emptyList(), emptyList()).toList()
        assertEquals("No fallback or repeat attempt for unchanged evidence", 1, calls)
        repeat(7) {
            clock += 45_000
            revision++
            assertEquals(listOf(ProviderEvent.TextDelta("Repository result"), ProviderEvent.Completed), wrapped.streamRound(emptyList(), emptyList()).toList())
        }
        assertEquals("New repository evidence cannot repeat failed optional narration in this run", 1, calls)
    }

    @Test fun unusableNarrationStopsOptionalAttemptsButANewRunCanTryAgain() = runTest {
        for (failure in listOf<suspend () -> AgentProviderSession>(
            { error("REASONING_OFF_UNSUPPORTED") },
            { session(ProviderEvent.Completed) },
            { session(ProviderEvent.TextDelta("All tests passed. $prose"), ProviderEvent.Completed) },
            { session(ProviderEvent.TextDelta(prose)) }
        )) {
            var clock = 0L
            var revision = 1L
            var calls = 0
            val summaries = mutableListOf<ApiState.ActivitySummary>()
            fun narrator() = GitHubProgressNarrator(
                snapshot = { state(revision) },
                openSelected = { _, _ ->
                    calls++
                    if (calls == 1) failure() else session(ProviderEvent.TextDelta(prose), ProviderEvent.Completed)
                },
                validate = {},
                admit = { _, _ -> true },
                publish = { summaries += it },
                now = { clock },
                deterministic = false
            )
            val firstRun = narrator()
            clock = 45_000
            firstRun.beforeRound()
            revision++
            clock += 45_000
            firstRun.beforeRound()
            assertEquals(1, calls)
            assertTrue(summaries.isEmpty())
            val nextRun = narrator()
            clock += 45_000
            nextRun.beforeRound()
            assertEquals(2, calls)
            assertEquals(prose, summaries.single().text)
        }
    }

    @Test fun finalResponseReservationAndBoundedNarrationBudgetPreventDispatch() = runTest {
        var clock = 0L
        val limited = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ -> error("No budget") },
            validate = {},
            admit = { _, _ -> true },
            publish = {},
            tokenBudget = 0,
            now = { clock },
            deterministic = false
        )
        val reserved = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ -> error("Final response budget is reserved") },
            validate = {},
            admit = { _, _ -> false },
            publish = {},
            now = { clock },
            deterministic = false
        )
        clock = 45_000
        limited.beforeRound()
        reserved.beforeRound()
    }

    @Test fun largeToolHistoryKeepsTheNextWorkRoundAheadOfOptionalProse() = runTest {
        var clock = 0L
        var calls = 0
        val narrator = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ ->
                calls++
                session(ProviderEvent.Completed)
            },
            validate = {},
            admit = { reservation, replay -> reservation + replay + 1000 <= 4000 },
            publish = {},
            now = { clock },
            deterministic = false
        )
        clock = 45_000
        narrator.beforeRound(workReplayTokens = 4000)
        assertEquals("Retained tool evidence owns the remaining request budget", 0, calls)
        narrator.beforeRound(workReplayTokens = 0)
        assertEquals(1, calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun slowSelectedModelTimesOutWithoutDelayingTheWorkIndefinitely() = runTest {
        var clock = 0L
        var revision = 1L
        var narrationCalls = 0
        var workRounds = 0
        val narrator = GitHubProgressNarrator(
            snapshot = { state(revision) },
            openSelected = { _, _ ->
                narrationCalls++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        kotlinx.coroutines.delay(20_000)
                        emit(ProviderEvent.TextDelta(prose))
                        emit(ProviderEvent.Completed)
                    }
                }
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { error("Incomplete prose must not publish") },
            now = { clock },
            deterministic = false
        )
        val work = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                workRounds++
                emit(ProviderEvent.Completed)
            }
        }
        clock = 45_000
        val wrapped = GitHubProgressSession(work, beforeRound = { narrator.beforeRound() }) {}
        wrapped.streamRound(emptyList(), emptyList()).toList()
        revision++
        clock += 45_000
        wrapped.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, workRounds)
        assertEquals(1, narrationCalls)
        assertEquals(8000L, testScheduler.currentTime)
    }

    @Test fun cancelledWorkDoesNotContinueIntoTheNextModelOrToolRound() = runTest {
        var clock = 0L
        var dispatched = false
        val narrator = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ ->
                dispatched = true
                error("Unexpected")
            },
            validate = { throw CancellationException("Stopped") },
            admit = { _, _ -> true },
            publish = {},
            now = { clock },
            deterministic = false
        )
        clock = 45_000
        try {
            narrator.beforeRound()
            error("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertFalse(dispatched)
        }
    }

    @Test fun narrationCannotInventCountersExposeReasoningOrClaimTaskCompletion() {
        val evidence = state().prompt()
        assertEquals(prose, GitHubProgressNarrator.validSummary(prose, evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary(prose.replace("job 7", "job 999"), evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("<think>private reasoning</think> $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("All complete. $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("All tests passed. $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("The repair was merged. $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("Seven tools were tested. $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("7 tools were tested. $prose", evidence))
        assertEquals(null, GitHubProgressNarrator.validSummary("2 authorized tools remain. $prose", evidence))
    }

    @Test fun newerFailureDuringValidationPreventsPublishingAnObsoleteFallback() = runTest {
        var clock = 0L
        var current = state()
        val summaries = mutableListOf<ApiState.ActivitySummary>()
        val narrator = GitHubProgressNarrator(
            snapshot = { current },
            openSelected = { _, _ -> error("Default progress must not invoke a model") },
            validate = { current = current.copy(revision = 2, failed = 2, recentSteps = listOf("The authentication request failed.")) },
            admit = { _, _ -> error("No inference allowance is needed") },
            publish = { summaries += it },
            now = { clock }
        )
        clock = 45_000
        narrator.beforeRound()
        assertTrue(summaries.isEmpty())
    }

    @Test fun newEvidenceDuringOptionalGenerationPreventsPublishingItsOutdatedProse() = runTest {
        var clock = 0L
        var current = state()
        val summaries = mutableListOf<ApiState.ActivitySummary>()
        val narrator = GitHubProgressNarrator(
            snapshot = { current },
            openSelected = { _, _ ->
                current = current.copy(revision = 2, failed = 2, recentSteps = listOf("The authentication request failed."))
                session(ProviderEvent.TextDelta(prose), ProviderEvent.Completed)
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { summaries += it },
            now = { clock },
            deterministic = false
        )
        clock = 45_000
        narrator.beforeRound()
        assertTrue(summaries.isEmpty())
    }

    @Test fun roleConstraintsKeepSelectedRouteAndAcceptedReasoningWithoutTools() {
        val constraints = dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints(lockSelectedRoute = true, allowReasoning = true, maximizeReasoning = true)
            .withoutTools().forRole("github-progress-summary")
        assertTrue(constraints.lockSelectedRoute)
        assertFalse(constraints.allowTools)
        assertFalse(constraints.allowGatewayLocalTools)
        assertTrue(constraints.allowReasoning)
        assertFalse(constraints.maximizeReasoning)
    }
}
