package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.media.session.PlaybackState
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.voice.SpeakingReplyRow
import dev.chungjungsoo.gptmobile.data.voice.VoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.VoiceTerminalReply
import dev.chungjungsoo.gptmobile.presentation.service.VoicePlaybackService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceBackgroundPlaybackTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val coordinator = VoiceBackgroundPlayback(application, Lazy { error("Replay already has its final text") }, Lazy { error("Unexpected database read") })

    private class Output : VoiceAudio {
        var finished: (() -> Unit)? = null
        var closed = false
        val played = mutableListOf<String>()
        override fun listen(listener: VoiceAudio.Listener) = error("Unexpected microphone")
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            played += text
            finished = complete
        }
        override fun stop() = Unit
        override fun close() {
            closed = true
        }
    }

    private fun start(room: Int): Pair<VoiceRuntime, Output> {
        val output = Output()
        lateinit var runtime: VoiceRuntime
        val session = VoiceSession(output, { false }, { coordinator.observe(runtime, room, it) })
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session)
        session.access(true, false, true)
        runtime.manualMessage.value = true
        assertTrue(session.beginMessage("reply-$room", manual = true))
        session.reply("reply-$room", "The current reply keeps its place.")
        assertNotNull(output.finished)
        assertTrue(coordinator.owns(runtime))
        return runtime to output
    }

    @Test fun releasingTheScreenKeepsPlaybackUntilTheOriginalAudioCompletes() {
        val (runtime, output) = start(17)
        assertEquals(VoicePlaybackService::class.java.name, shadowOf(application).nextStartedService.component?.className)
        coordinator.releaseScreenOwner(runtime)
        assertFalse(output.closed)
        assertEquals(17, coordinator.notification.value?.roomId)
        output.finished!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(output.closed)
        assertNull(coordinator.notification.value)
    }

    @Test fun aDetachedStreamingReplyFinishesFromItsOriginalDatabaseOwner() {
        val room = 17
        val message = MessageV2(id = 5, content = "The first sentence is ready. ", platformType = "test", currentRunId = "run", ownershipFence = 12)
        val finalText = message.content + "The reply finishes in the background."
        val rows = MutableStateFlow<SpeakingReplyRow?>(SpeakingReplyRow(message.content, AgentRunStatus.RUNNING))
        val dao = mockk<AgentPersistenceDao>()
        every { dao.observeSpeakingReply(room, 5, "run", 12) } returns rows
        val run = mockk<AgentRun> {
            every { status } returns AgentRunStatus.COMPLETED
            every { terminalError } returns null
        }
        val repository = mockk<ChatRepository>()
        coEvery { repository.terminalVoiceReply(room, 5, "run", 12) } returns VoiceTerminalReply(message.copy(content = finalText), run)
        val owner = VoiceBackgroundPlayback(application, Lazy { dao }, Lazy { repository })
        val output = Output()
        lateinit var runtime: VoiceRuntime
        val session = VoiceSession(output, { false }, { owner.observe(runtime, room, it) })
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session)
        session.access(true, false, true)
        runtime.playback.observe(emptyList(), true)
        runtime.playback.observe(runtime.replyProjection.update(listOf(listOf(message)), 1) { AgentRunStatus.RUNNING }, true)
        val key = requireNotNull(runtime.playback.next()).identity.key
        assertTrue(session.beginMessage(key))
        session.streamReply(key, message.content)
        assertNotNull(output.finished)
        owner.releaseScreenOwner(runtime)
        rows.value = SpeakingReplyRow(finalText, AgentRunStatus.COMPLETED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(finalText, session.state.reply)
        assertTrue(session.state.replyComplete)
        repeat(4) { output.finished?.also { output.finished = null }?.invoke() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(finalText.trim(), output.played.joinToString(" ").replace(Regex("""\s+"""), " ").trim())
        assertNull(owner.notification.value)
        assertTrue(output.closed)
    }

    @Test fun systemMediaControlsAdvertiseAStopActionBoundToThePlayingReply() {
        val state = VoicePlaybackService.playingState("first")
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertTrue(state.actions and PlaybackState.ACTION_STOP != 0L)
        assertEquals("Stop", state.customActions.single().name)
        assertFalse(state.customActions.single().action == VoicePlaybackService.playingState("second").customActions.single().action)
    }

    @Test fun switchingRoomsAndTappingAnOldNotificationCannotStopTheNewReply() {
        val (first, _) = start(11)
        val stale = requireNotNull(coordinator.notification.value).token
        coordinator.detach(first)
        val (second, _) = start(22)
        coordinator.stop(stale)
        assertEquals(VoicePhase.PAUSED, first.session.state.phase)
        assertTrue(coordinator.owns(second))
        coordinator.stop(requireNotNull(coordinator.notification.value).token)
        assertEquals(VoicePhase.PAUSED, second.session.state.phase)
        assertNull(coordinator.notification.value)
    }
}
