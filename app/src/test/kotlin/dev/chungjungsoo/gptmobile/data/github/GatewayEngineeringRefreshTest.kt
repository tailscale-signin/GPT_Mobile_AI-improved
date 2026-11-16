package dev.chungjungsoo.gptmobile.data.github

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class GatewayEngineeringRefreshTest {
    private val conversations = mockk<GatewayConversationRepository>()
    private val repository = spyk(GatewayEngineeringRepository(mockk(), mockk(), mockk(), mockk(), mockk(), conversations, mockk(), mockk()))
    private val first = binding("first")
    private val second = binding("second")

    @Test fun unavailableJobStillRefreshesLaterJobsAndTranscriptBeforeReportingFailure() = runTest {
        val failure = IllegalStateException("Original profile unavailable")
        coEvery { repository.bindings(1) } returns listOf(first, second)
        coEvery { repository.observe("first") } throws failure
        coEvery { repository.observe("second") } returns second
        coEvery { conversations.refreshChat(1) } returns Unit

        assertSame(failure, runCatching { repository.refreshChat(1) }.exceptionOrNull())
        coVerifyOrder {
            repository.observe("first")
            repository.observe("second")
            conversations.refreshChat(1)
        }
        coVerify(exactly = 0) { repository.reconnect(any(), any()) }
    }

    @Test fun cancellationStopsRemainingJobsAndTranscriptRefresh() = runTest {
        val cancelled = CancellationException("Chat closed")
        coEvery { repository.bindings(1) } returns listOf(first, second)
        coEvery { repository.observe("first") } throws cancelled

        assertSame(cancelled, runCatching { repository.refreshChat(1) }.exceptionOrNull())
        coVerify(exactly = 0) { repository.observe("second") }
        coVerify(exactly = 0) { conversations.refreshChat(any()) }
    }

    private fun binding(id: String) = GatewayEngineeringBinding(
        profileUid = "profile",
        endpoint = "https://gateway.invalid/v1",
        request = GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect", "selected", chatId = 1, requestId = id)
    )
}
