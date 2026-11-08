package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRejectedObservationTest {
    @Test fun definitiveRejectionDoesNotScheduleOrPollForever() = runTest {
        val store = mockk<GatewayWorkStore>()
        val dao = mockk<GatewayWorkDao>()
        val client = mockk<GatewayEngineeringClient>()
        val conversations = mockk<GatewayConversationRepository>()
        val request = GatewayEngineeringRequest("owner/repo", "feature", "feature", "Read", "model", chatId = 7, requestId = "rejected")
        val binding = GatewayEngineeringBinding("gateway", "http://127.0.0.1/v1", request)
        val command = GatewayCommandRecord("rejected", "scope", null, 7, null, null, null, "create", "digest", "{}", "REJECTED", null, 1, 1, "COMMAND_ID_CONFLICT")
        every { store.dao } returns dao
        coEvery { store.bindings(null) } returns listOf(binding)
        coEvery { dao.command("rejected") } returns command
        val repository = GatewayEngineeringRepository(mockk<Context>(), mockk<SettingRepository>(), client, mockk(), store, conversations)
        repository.schedule()
        assertTrue(repository.syncAll())
        coVerify(exactly = 0) { conversations.observeRequest(any()) }
        coVerify(exactly = 0) { client.get(any(), any(), any()) }
        coVerify(exactly = 0) { client.create(any(), any()) }
    }
}
