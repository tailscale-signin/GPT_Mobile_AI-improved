package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayLocalTranscriptPage
import dev.chungjungsoo.gptmobile.data.github.GatewayTranscriptPart
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayTranscriptViewModelTest {
    private val repository = mockk<GatewayConversationRepository>()
    private val settings = mockk<SettingRepository>()
    private val first = GatewayLocalTranscriptPage("canonical", "Saved title", null, false, "Some earlier messages are unavailable", listOf(GatewayTranscriptPart("part-1", "message", "assistant", "message", "First part", 0, 2, false)), "opaque-next")
    private val last = first.copy(items = listOf(first.items.single().copy(messageId = "part-2", content = "Second part", partIndex = 1)), nextCursor = null)

    @Test fun openingAndPagingAnUnattachedTranscriptDoesNotCreateChatOrContactTheServer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { settings.fetchPlatformV2s() } returns emptyList()
            coEvery { repository.transcriptPage("canonical", null) } returns first
            coEvery { repository.transcriptPage("canonical", "opaque-next") } returns last
            val model = GatewayTranscriptViewModel(repository, settings).also { store.put("transcript", it) }
            model.open("canonical")
            val initial = model.state.first { it.page != null && !it.loading }
            assertEquals(first, initial.page)
            assertFalse(requireNotNull(initial.page).historyComplete)
            model.next()
            val next = model.state.first { it.cursor == "opaque-next" && !it.loading }
            assertEquals(last, next.page)
            assertEquals(listOf(null), next.previousCursors)
            model.previous()
            assertEquals(first, model.state.first { it.cursor == null && !it.loading }.page)
            coVerify(exactly = 0) { repository.refreshTranscript(any()) }
            coVerify(exactly = 0) { repository.openConversation(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.attach(any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun aDisconnectedExplicitRefreshKeepsPreviouslyRetainedPartsReadable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { settings.fetchPlatformV2s() } returns emptyList()
            coEvery { repository.transcriptPage("canonical", null) } returns first
            coEvery { repository.refreshTranscript("canonical") } throws IOException("Disconnected")
            val model = GatewayTranscriptViewModel(repository, settings).also { store.put("transcript", it) }
            model.open("canonical")
            model.state.first { it.page != null && !it.loading }
            model.reconnect()
            val failed = model.state.first { it.error != null && !it.loading }
            assertEquals(first, failed.page)
            assertEquals("Disconnected", failed.error)
            coVerify(exactly = 1) { repository.refreshTranscript("canonical") }
            coVerify(exactly = 0) { repository.openConversation(any(), any(), any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun aRepeatedCursorCannotReplaceTheLastVerifiedPageOrLoop() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { settings.fetchPlatformV2s() } returns emptyList()
            coEvery { repository.transcriptPage("canonical", null) } returns first
            coEvery { repository.transcriptPage("canonical", "opaque-next") } returns last.copy(nextCursor = "opaque-next")
            val model = GatewayTranscriptViewModel(repository, settings).also { store.put("transcript", it) }
            model.open("canonical")
            model.state.first { it.page != null && !it.loading }
            model.next()
            val failed = model.state.first { it.error != null && !it.loading }
            assertEquals(first, failed.page)
            coVerify(exactly = 1) { repository.transcriptPage("canonical", "opaque-next") }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun boundedTextSlicesRetainEveryCharacterWithoutSplittingSurrogatePairs() {
        val text = "a".repeat(8191) + "\uD83D\uDE80" + "b".repeat(12000)
        val slices = gatewayTranscriptSlices(text)
        assertEquals(text, slices.joinToString(""))
        assertTrue(slices.all { it.length <= 8193 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertEquals(listOf(""), gatewayTranscriptSlices(""))
    }
}
