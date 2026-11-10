package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResponse
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
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "status" }, any(), any()) } returns response(indexed)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedArchitectureCannotRelabelStatusAndFailedRefreshRetainsTheLastMap() = runTest {
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "architecture" }, any(), any()) } throws IllegalStateException("Gateway disconnected")
        model.request("architecture")
        advanceUntilIdle()
        assertEquals("status", model.state.value.resultOperation)
        assertNull(model.state.value.architecture)
        assertEquals("Gateway disconnected", model.state.value.error)
        val map = json("""{"dependencies":{"a.kt":["b.kt"]}}""")
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "architecture" }, any(), any()) } returns response(map, "architecture")
        model.request("architecture")
        advanceUntilIdle()
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "architecture" }, any(), any()) } throws IllegalStateException("Gateway disconnected")
        model.request("architecture")
        advanceUntilIdle()
        assertEquals(map, model.state.value.architecture)
        assertEquals("Gateway disconnected", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun returningToTheSameRepositoryRestartsACancelledInitialLoad() = runTest {
        var requests = 0
        coEvery { graphs.executeForMap(any(), any(), any(), any()) } coAnswers {
            if (++requests == 1) awaitCancellation() else response(indexed)
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
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "index" }, any(), any()) } returns response(json("""{"has_more":true,"index_cursor":"page-1"}"""), "index")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        coVerify(exactly = 2) { graphs.executeForMap(context, match { it.graphText("action") == "index" }, any(), any()) }
        assertTrue(model.state.value.error.orEmpty().contains("repeated a page"))
        assertEquals("page-1", model.state.value.indexCursor)
        assertFalse(model.state.value.busy)
    }

    @Test fun largeIndexesPauseAtABoundedPageCountAndContinueFromTheSavedCursor() = runTest {
        var pages = 0
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "index" }, any(), any()) } coAnswers {
            pages++
            if (pages <= RepositoryGraphViewModel.MAX_INDEX_PAGES) response(json("""{"has_more":true,"index_cursor":"page-$pages"}"""), "index") else response(json("""{"has_more":false}"""), "index")
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
        coVerify(exactly = 1) { graphs.executeForMap(context, match { it.graphText("action") == "index" && it.graphText("index_cursor") == cursor }, any(), any()) }
        assertNull(model.state.value.indexCursor)
        assertFalse(model.state.value.indexingPaused)
        assertEquals("status", model.state.value.resultOperation)
    }

    @Test fun switchingRepositoriesRejectsALateResultFromThePreviousSelection() = runTest {
        coEvery { graphs.executeForMap(context, any(), any(), any()) } coAnswers {
            withContext(NonCancellable) {
                delay(100)
                response(json("""{"repository":"old"}"""))
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
        coEvery { graphs.executeForMap(context, match { it.graphText("action") == "architecture" }, any(), any()) } coAnswers {
            if (++attempts == 1) awaitCancellation() else response(map, "architecture")
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
        coVerify(exactly = 1) { graphs.executeForMap(context, any(), any(), any()) }
    }

    @Test fun missingContinuationAndRequestTimeoutProduceActionableFailures() = runTest {
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "index" }, any(), any()) } returns response(json("""{"has_more":true}"""), "index")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        assertTrue(model.state.value.error.orEmpty().contains("next indexing cursor"))
        coEvery { graphs.executeForMap(any(), match { it.graphText("action") == "architecture" }, any(), any()) } coAnswers {
            delay(100_000)
            response(indexed)
        }
        model.request("architecture")
        advanceUntilIdle()
        assertTrue(model.state.value.error.orEmpty().contains("timed out"))
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.architecture)
    }

    @Test fun explicitGatewayChangeClearsTheOldMapAndRejectsItsLateResponse() = runTest {
        val model = RepositoryGraphViewModel(graphs)
        coEvery { graphs.executeForMap(context, any(), "first-host", any()) } coAnswers {
            withContext(NonCancellable) {
                delay(100)
                GatewayGraphResponse(indexed, "status", "first-host", "First")
            }
        }
        coEvery { graphs.executeForMap(context, any(), "second-host", any()) } returns GatewayGraphResponse(indexed, "status", "second-host", "Second")
        model.bind(context, "first-host")
        runCurrent()
        model.bind(context, "second-host")
        assertNull(model.state.value.result)
        advanceUntilIdle()
        assertEquals("second-host", model.state.value.gatewayProfileUid)
        assertEquals("Second", model.state.value.gatewayName)
    }

    @Test fun unconfirmedIndexCannotBeRepeatedAndReadOnlyStatusAdoptsTheSavedCursor() = runTest {
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        coEvery { graphs.executeForMap(context, match { it.graphText("action") == "index" }, any(), any()) } throws GatewayGraphOperationException(IllegalStateException("Response lost"), "gateway", "Selected gateway", "CHECK_SAVED_INDEX", true)
        model.request("index")
        advanceUntilIdle()
        model.request("index")
        advanceUntilIdle()
        coVerify(exactly = 1) { graphs.executeForMap(context, match { it.graphText("action") == "index" }, any(), any()) }
        assertTrue(model.state.value.indexOutcomeUnknown)
        coEvery { graphs.executeForMap(context, match { it.graphText("action") == "status" }, any(), any()) } returns response(json("""{"has_more":true,"index_cursor":"saved-page"}"""))
        model.request("status")
        advanceUntilIdle()
        assertFalse(model.state.value.indexOutcomeUnknown)
        assertEquals("saved-page", model.state.value.indexCursor)
    }

    @Test fun editingTheSameGatewayUidHidesItsRetiredBindingAndRejectsALateMap() = runTest {
        val original = PlatformV2(uid = "gateway", name = "Selected PC", apiUrl = "http://127.0.0.1:8090/v1", model = "unused")
        val edited = original.copy(apiUrl = "http://127.0.0.1:8091/v1")
        var requests = 0
        coEvery { graphs.executeForMap(context, any(), original.uid, any()) } coAnswers {
            if (++requests == 1) {
                withContext(NonCancellable) {
                    delay(100)
                    response(json("""{"repository":"retired-host"}"""))
                }
            } else {
                response(indexed)
            }
        }
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context, original.uid, original)
        runCurrent()
        assertFalse(model.isBoundTo(context, edited.uid, edited))
        model.bind(context, edited.uid, edited)
        assertNull(model.state.value.result)
        assertNull(model.state.value.architecture)
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(indexed, model.state.value.result)
        assertTrue(model.isBoundTo(context, edited.uid, edited))
    }

    @Test fun statusLoadsTheRealArchitectureForBothMapEntryPoints() = runTest {
        val saved = json("""{"has_more":false,"graph":{"commit_sha":"${context.commitSha}"}}""")
        val architecture = json("""{"graph":{"commit_sha":"${context.commitSha}"},"dependencies":{"a.kt":["b.kt"]}}""")
        coEvery { graphs.executeForMap(context, match { it.graphText("action") == "status" }, any(), any()) } returns response(saved)
        coEvery { graphs.executeForMap(context, match { it.graphText("action") == "architecture" }, any(), any()) } returns response(architecture, "architecture")
        val model = RepositoryGraphViewModel(graphs)
        model.bind(context)
        advanceUntilIdle()
        assertEquals(saved, model.state.value.result)
        assertEquals("status", model.state.value.resultOperation)
        assertEquals(architecture, model.state.value.architecture)
    }

    private fun response(value: JsonObject, operation: String = "status", unknown: Boolean = false) = GatewayGraphResponse(value, operation, "gateway", "Selected gateway", unknown)

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
