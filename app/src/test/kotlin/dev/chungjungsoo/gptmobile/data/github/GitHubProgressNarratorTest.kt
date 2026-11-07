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
        var calls = 0
        val summaries = mutableListOf<ApiState.ActivitySummary>()
        val narrator = GitHubProgressNarrator(
            snapshot = { state(revision) },
            openSelected = { prompt, cap ->
                calls++
                assertTrue(prompt.contains("Job 7"))
                assertEquals(192, cap)
                session(ProviderEvent.TextDelta(prose), ProviderEvent.Completed)
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { summaries += it },
            now = { clock }
        )
        clock = 14_999
        narrator.beforeRound()
        assertEquals(0, calls)
        clock = 15_000
        narrator.beforeRound()
        assertEquals(1, calls)
        assertTrue(summaries.single().modelAuthored)
        clock = 30_000
        narrator.beforeRound()
        assertEquals(1, calls)
        revision++
        narrator.beforeRound()
        assertEquals(2, calls)
        assertEquals("Identical prose does not create another summary", 1, summaries.size)
    }

    @Test fun optionalGenerationFailureNeverRetriesOrBlocksRepositoryWork() = runTest {
        var clock = 0L
        var calls = 0
        val narrator = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ ->
                calls++
                session(ProviderEvent.Failed("Selected model unavailable"))
            },
            validate = {},
            admit = { _, _ -> true },
            publish = { error("Failed generation cannot become authored progress") },
            now = { clock }
        )
        val work = session(ProviderEvent.TextDelta("Repository result"), ProviderEvent.Completed)
        val wrapped = GitHubProgressSession(work, beforeRound = { narrator.beforeRound() }) {}
        clock = 15_000
        assertEquals(listOf(ProviderEvent.TextDelta("Repository result"), ProviderEvent.Completed), wrapped.streamRound(emptyList(), emptyList()).toList())
        clock = 30_000
        wrapped.streamRound(emptyList(), emptyList()).toList()
        assertEquals("No fallback or repeat attempt for unchanged evidence", 1, calls)
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
            now = { clock }
        )
        val reserved = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ -> error("Final response budget is reserved") },
            validate = {},
            admit = { _, _ -> false },
            publish = {},
            now = { clock }
        )
        clock = 15_000
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
            now = { clock }
        )
        clock = 15_000
        narrator.beforeRound(workReplayTokens = 4000)
        assertEquals("Retained tool evidence owns the remaining request budget", 0, calls)
        narrator.beforeRound(workReplayTokens = 0)
        assertEquals(1, calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun slowSelectedModelTimesOutWithoutDelayingTheWorkIndefinitely() = runTest {
        var clock = 0L
        var workStarted = false
        val narrator = GitHubProgressNarrator(
            snapshot = { state() },
            openSelected = { _, _ ->
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
            now = { clock }
        )
        val work = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                workStarted = true
                emit(ProviderEvent.Completed)
            }
        }
        clock = 15_000
        GitHubProgressSession(work, beforeRound = { narrator.beforeRound() }) {}.streamRound(emptyList(), emptyList()).toList()
        assertTrue(workStarted)
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
            now = { clock }
        )
        clock = 15_000
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

    @Test fun roleConstraintsKeepTheSelectedRouteAndDisableToolsAndReasoning() {
        val constraints = dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints(lockSelectedRoute = true, allowReasoning = true, maximizeReasoning = true)
            .withoutTools().forRole("github-progress-summary")
        assertTrue(constraints.lockSelectedRoute)
        assertFalse(constraints.allowTools)
        assertFalse(constraints.allowGatewayLocalTools)
        assertFalse(constraints.allowReasoning)
        assertFalse(constraints.maximizeReasoning)
    }
}
