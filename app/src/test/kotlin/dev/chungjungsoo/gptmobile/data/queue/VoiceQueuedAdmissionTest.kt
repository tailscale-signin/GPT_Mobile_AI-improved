package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceQueuedAdmissionTest {
    @Test fun consumedQueueReceiptResolvesTheOriginalRequestAfterItLeavesPendingList() = runTest {
        val original = PendingPrompt("queue-1", 7, "Dictated words", Json.encodeToString(PendingPromptPayload(requestId = "voice-request")), 1)
        val unrelated = original.copy(id = "queue-2", payload = Json.encodeToString(PendingPromptPayload(requestId = "other-request")), position = 2, userMessageId = 99)
        var retained = listOf(original, unrelated)
        val pending = MutableStateFlow(listOf(original))
        val dao = mockk<PendingPromptDao>()
        every { dao.observePending() } returns pending
        coEvery { dao.forChat(7) } answers { retained }
        val database = mockk<ChatDatabaseV2>()
        every { database.pendingPromptDao() } returns dao
        val queue = DurablePromptQueue(database, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), ApplicationProvider.getApplicationContext(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val observed = mutableListOf<PendingPrompt?>()
        val job = launch { queue.observeRequest(7, "voice-request").take(2).toList(observed) }
        runCurrent()
        retained = listOf(original.copy(userMessageId = 71), unrelated)
        pending.value = emptyList()
        runCurrent()
        job.join()
        assertEquals(listOf(null, 71), observed.map { it?.userMessageId })
        assertEquals(listOf("queue-1", "queue-1"), observed.map { it?.id })
    }
}
