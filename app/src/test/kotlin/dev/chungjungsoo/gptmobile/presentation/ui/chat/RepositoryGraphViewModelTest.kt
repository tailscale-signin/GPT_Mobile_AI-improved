package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryGraphViewModelTest {
    private val graphs = mockk<GatewayGraphRepository>()
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "selected-profile")
    private val indexed = json("""{"status":"INDEXED"}""")

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { graphs.execute(any(), match { it.graphText("action") == "status" }) } returns indexed
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedArchitectureCannotRelabelStatusAndFailedRefreshRetainsTheLastMap() = runTest {
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        coEvery { graphs.execute(any(), match { it.graphText("action") == "architecture" }) } throws IllegalStateException("Gateway disconnected")
        model.request("architecture")
        advanceUntilIdle()
        assertEquals("status", model.state.value.resultOperation)
        assertNull(model.state.value.architecture)
        assertEquals("Gateway disconnected", model.state.value.error)
        val map = json("""{"dependencies":{"a.kt":["b.kt"]}}""")
        coEvery { graphs.execute(any(), match { it.graphText("action") == "architecture" }) } returns map
        model.request("architecture")
        advanceUntilIdle()
        coEvery { graphs.execute(any(), match { it.graphText("action") == "architecture" }) } throws IllegalStateException("Gateway disconnected")
        model.request("architecture")
        advanceUntilIdle()
        assertEquals(map, model.state.value.architecture)
        assertEquals("Gateway disconnected", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun returningToTheSameRepositoryRestartsACancelledInitialLoad() = runTest {
        var requests = 0
        coEvery { graphs.execute(any(), any()) } coAnswers {
            if (++requests == 1) awaitCancellation() else indexed
        }
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        runCurrent()
        model.stop()
        model.bind(context)
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(indexed, model.state.value.result)
        assertFalse(model.state.value.busy)
    }

    @Test fun repeatedIndexCursorStopsAndRetainsTheLastResumePoint() = runTest {
        coEvery { graphs.execute(any(), match { it.graphText("action") == "index" }) } returns json("""{"has_more":true,"index_cursor":"page-1"}""")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        coVerify(exactly = 2) { graphs.execute(context, match { it.graphText("action") == "index" }) }
        assertTrue(model.state.value.error.orEmpty().contains("repeated a page"))
        assertEquals("page-1", model.state.value.indexCursor)
        assertFalse(model.state.value.busy)
    }

    @Test fun largeIndexesPauseAtABoundedPageCountAndContinueFromTheSavedCursor() = runTest {
        var pages = 0
        coEvery { graphs.execute(any(), match { it.graphText("action") == "index" }) } coAnswers {
            pages++
            if (pages <= RepositoryGraphViewModel.MAX_INDEX_PAGES) json("""{"has_more":true,"index_cursor":"page-$pages"}""") else json("""{"has_more":false}""")
        }
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        assertEquals(RepositoryGraphViewModel.MAX_INDEX_PAGES, pages)
        assertTrue(model.state.value.indexingPaused)
        assertFalse(model.state.value.busy)
        val cursor = model.state.value.indexCursor
        model.request("index")
        advanceUntilIdle()
        coVerify(exactly = 1) { graphs.execute(context, match { it.graphText("action") == "index" && it.graphText("index_cursor") == cursor }) }
        assertNull(model.state.value.indexCursor)
        assertFalse(model.state.value.indexingPaused)
        assertEquals("status", model.state.value.resultOperation)
    }

    @Test fun switchingRepositoriesRejectsALateResultFromThePreviousSelection() = runTest {
        coEvery { graphs.execute(context, any()) } coAnswers {
            withContext(NonCancellable) {
                delay(100)
                json("""{"repository":"old"}""")
            }
        }
        val next = context.copy(commitSha = "b".repeat(40), mainProfileUid = "next-profile")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        runCurrent()
        model.bind(next)
        assertEquals(next, model.state.value.boundContext)
        assertNull(model.state.value.architecture)
        assertNull(model.state.value.result)
        advanceUntilIdle()
        assertEquals(indexed, model.state.value.result)
        assertNull(model.state.value.error)
    }

    @Test fun cancelledArchitectureCanBeRequestedAgainForTheRetainedRevision() = runTest {
        val map = json("""{"dependencies":{"a.kt":["b.kt"]}}""")
        var attempts = 0
        coEvery { graphs.execute(context, match { it.graphText("action") == "architecture" }) } coAnswers {
            if (++attempts == 1) awaitCancellation() else map
        }
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("architecture")
        runCurrent()
        model.stop()
        model.bind(context)
        assertEquals("status", model.state.value.resultOperation)
        model.request("architecture")
        advanceUntilIdle()
        assertEquals(map, model.state.value.architecture)
        assertEquals(context, model.state.value.boundContext)
        assertEquals(2, attempts)
    }

    @Test fun rapidTapsAdmitOnlyOneRequestBeforeTheCoroutineStarts() = runTest {
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        model.request("status")
        model.request("status")
        advanceUntilIdle()
        coVerify(exactly = 1) { graphs.execute(context, any()) }
    }

    @Test fun missingContinuationAndRequestTimeoutProduceActionableFailures() = runTest {
        coEvery { graphs.execute(any(), match { it.graphText("action") == "index" }) } returns json("""{"has_more":true}""")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        assertTrue(model.state.value.error.orEmpty().contains("next indexing cursor"))
        coEvery { graphs.execute(any(), match { it.graphText("action") == "architecture" }) } coAnswers {
            delay(100_000)
            indexed
        }
        model.request("architecture")
        advanceUntilIdle()
        assertTrue(model.state.value.error.orEmpty().contains("timed out"))
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.architecture)
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
