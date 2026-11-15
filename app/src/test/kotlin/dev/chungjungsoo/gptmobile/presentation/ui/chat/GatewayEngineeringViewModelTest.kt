package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringCheckpoints
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayEngineeringViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<GatewayEngineeringRepository>()
    private val first = binding(1)
    private val second = binding(2)
    private lateinit var model: GatewayEngineeringViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.bindings(first.chatId) } returns listOf(first)
        coEvery { repository.bindings(second.chatId) } returns listOf(second)
        coEvery { repository.options() } returns GatewayEngineeringSettings()
        model = GatewayEngineeringViewModel(repository, dispatcher)
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun queuedCheckpointsCannotPublishIntoAnotherChatAndDuplicateTapsRemainReserved() = runTest(dispatcher) {
        val firstResult = GatewayEngineeringCheckpoints("job-1", 1, state = "RUNNING")
        val secondResult = GatewayEngineeringCheckpoints("job-2", 2, state = "COMPLETED")
        coEvery { repository.checkpoints(first.request.requestId) } returns firstResult
        coEvery { repository.checkpoints(second.request.requestId) } returns secondResult
        model.bind(first.chatId)
        runCurrent()
        assertEquals(listOf(first), model.state.value.bindings)

        model.checkpoints(first)
        model.checkpoints(first)
        assertTrue(model.state.value.busy)
        coVerify(exactly = 0) { repository.checkpoints(any()) }
        model.bind(second.chatId)
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.bindings.isEmpty())
        assertNull(model.state.value.checkpoints)
        runCurrent()

        coVerify(exactly = 1) { repository.checkpoints(first.request.requestId) }
        assertEquals(listOf(second), model.state.value.bindings)
        assertNull(model.state.value.checkpoints)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)

        model.checkpoints(second)
        runCurrent()
        coVerify(exactly = 1) { repository.checkpoints(second.request.requestId) }
        assertEquals(second to secondResult, model.state.value.checkpoints)
        assertEquals(listOf(second), model.state.value.bindings)
        assertFalse(model.state.value.busy)
    }

    @Test fun queuedCandidateDetailsCannotPublishIntoAnotherChat() = runTest(dispatcher) {
        coEvery { repository.candidateDetails(first.request.requestId, "candidate-1") } returns "first chat candidate"
        coEvery { repository.candidateDetails(second.request.requestId, "candidate-2") } returns "second chat candidate"
        model.bind(first.chatId)
        runCurrent()
        assertEquals(listOf(first), model.state.value.bindings)

        model.inspectCandidate(first, "candidate-1")
        assertTrue(model.state.value.busy)
        coVerify(exactly = 0) { repository.candidateDetails(any(), any()) }
        model.bind(second.chatId)
        assertNull(model.state.value.evidencePreview)
        runCurrent()

        coVerify(exactly = 1) { repository.candidateDetails(first.request.requestId, "candidate-1") }
        assertEquals(listOf(second), model.state.value.bindings)
        assertNull(model.state.value.evidencePreview)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)

        model.inspectCandidate(second, "candidate-2")
        runCurrent()
        coVerify(exactly = 1) { repository.candidateDetails(second.request.requestId, "candidate-2") }
        assertEquals("second chat candidate", model.state.value.evidencePreview)
        assertEquals(listOf(second), model.state.value.bindings)
        assertFalse(model.state.value.busy)
    }

    @Test fun queuedEvidenceCannotPublishIntoAnotherChat() = runTest(dispatcher) {
        coEvery { repository.evidence(first.request.requestId, "receipt-1") } returns "first chat evidence"
        coEvery { repository.evidence(second.request.requestId, "receipt-2") } returns "second chat evidence"
        model.bind(first.chatId)
        runCurrent()
        assertEquals(listOf(first), model.state.value.bindings)

        model.evidence(first, "receipt-1")
        assertTrue(model.state.value.busy)
        coVerify(exactly = 0) { repository.evidence(any(), any()) }
        model.bind(second.chatId)
        assertNull(model.state.value.evidencePreview)
        runCurrent()

        coVerify(exactly = 1) { repository.evidence(first.request.requestId, "receipt-1") }
        assertEquals(listOf(second), model.state.value.bindings)
        assertNull(model.state.value.evidencePreview)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)

        model.evidence(second, "receipt-2")
        runCurrent()
        coVerify(exactly = 1) { repository.evidence(second.request.requestId, "receipt-2") }
        assertEquals("second chat evidence", model.state.value.evidencePreview)
        assertEquals(listOf(second), model.state.value.bindings)
        assertFalse(model.state.value.busy)
    }

    private fun binding(chatId: Int) = GatewayEngineeringBinding(
        profileUid = "gateway-$chatId",
        endpoint = "https://gateway-$chatId.invalid/v1",
        request = GatewayEngineeringRequest(
            repository = "owner/repo-$chatId",
            branch = "work-$chatId",
            base = "main",
            objective = "Saved task $chatId",
            model = "model-$chatId",
            chatId = chatId,
            requestId = "request-$chatId"
        )
    )
}
