package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.voice.SpeakingReplyRow
import dev.chungjungsoo.gptmobile.data.voice.VoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.VoiceTerminalReply
import dev.chungjungsoo.gptmobile.data.voice.VoiceThinkingCue
import dev.chungjungsoo.gptmobile.presentation.service.VoicePlaybackService
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaSession

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
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session, coordinator)
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
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session, owner)
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

    @Test fun aDetachedAcknowledgmentHandsOffStableWordsDuringAProviderPause() {
        val room = 17
        val message = MessageV2(id = 5, chatId = room, content = "", platformType = "test", currentRunId = "run", ownershipFence = 12)
        val rows = MutableStateFlow<SpeakingReplyRow?>(SpeakingReplyRow("", AgentRunStatus.RUNNING))
        val dao = mockk<AgentPersistenceDao>()
        every { dao.observeSpeakingReply(room, 5, "run", 12) } returns rows
        val owner = VoiceBackgroundPlayback(application, Lazy { dao }, Lazy { error("No terminal row was emitted") })
        val audio = Output()
        lateinit var runtime: VoiceRuntime
        val session = VoiceSession(audio, { false }, { owner.observe(runtime, room, it) }, now = SystemClock::elapsedRealtime)
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session, owner)
        session.performanceMode = VoicePerformanceMode.BALANCED
        session.access(true, false, true)
        runtime.playback.observe(emptyList(), true)
        runtime.playback.observe(runtime.replyProjection.update(listOf(listOf(message)), 1) { AgentRunStatus.RUNNING }, true)
        val key = requireNotNull(runtime.playback.next(VoiceMessageIdentity(5, "run"))).identity.key
        assertTrue(session.beginMessage(key))
        session.offerOpening(key, VoiceThinkingCue("Un instant."))
        val finishCue = requireNotNull(audio.finished)
        owner.releaseScreenOwner(runtime)
        rows.value = SpeakingReplyRow("Voici deux ", AgentRunStatus.RUNNING)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        finishCue()
        assertEquals(listOf("Un instant."), audio.played)
        looper.idleFor(121, TimeUnit.MILLISECONDS)
        assertEquals(listOf("Un instant.", "Voici deux "), audio.played)
        owner.stopCurrent()
        looper.idle()
        assertTrue(audio.closed)
    }

    @Test fun aFinalAnswerArrivingDuringADetachedCueKeepsItsServiceUntilTheAnswerDrains() =
        checkDetachedHandoff(completeCueEarly = false, remaining = "La capitale est Paris.")

    @Test fun aFinalAnswerAfterADetachedCueStartsFromAnEmptyQueue() =
        checkDetachedHandoff(completeCueEarly = true, remaining = "La capitale est Paris.")

    @Test fun aFinalReplyContainingOnlyTheHeardCueReleasesTheService() =
        checkDetachedHandoff(completeCueEarly = true, remaining = null)

    private fun checkDetachedHandoff(completeCueEarly: Boolean, remaining: String?) {
        val room = 23
        val question = MessageV2(id = 9, chatId = room, content = "", platformType = "test", currentRunId = "r", ownershipFence = 3)
        val answer = listOfNotNull("Un instant.", remaining).joinToString(" ")
        val rows = MutableStateFlow<SpeakingReplyRow?>(SpeakingReplyRow("", AgentRunStatus.RUNNING))
        val dao = mockk<AgentPersistenceDao>()
        every { dao.observeSpeakingReply(room, 9, "r", 3) } returns rows
        val run = mockk<AgentRun> {
            every { status } returns AgentRunStatus.COMPLETED
            every { terminalError } returns null
        }
        val repository = mockk<ChatRepository>()
        coEvery { repository.terminalVoiceReply(room, 9, "r", 3) } returns VoiceTerminalReply(question.copy(content = answer), run)
        val owner = VoiceBackgroundPlayback(application, Lazy { dao }, Lazy { repository })
        val audio = Output()
        lateinit var runtime: VoiceRuntime
        val session = VoiceSession(audio, { false }, { owner.observe(runtime, room, it) })
        runtime = VoiceRuntime(mockk(), mockk(), null, mockk(), session, owner)
        session.access(true, false, true)
        runtime.playback.observe(emptyList(), true)
        runtime.playback.observe(runtime.replyProjection.update(listOf(listOf(question)), 1) { AgentRunStatus.RUNNING }, true)
        val key = requireNotNull(runtime.playback.next(VoiceMessageIdentity(9, "r"))).identity.key
        assertTrue(session.beginMessage(key))
        session.offerOpening(key, VoiceThinkingCue("Un instant."))
        val token = requireNotNull(owner.notification.value).token
        val finishCue = requireNotNull(audio.finished)
        owner.releaseScreenOwner(runtime)
        if (completeCueEarly) finishCue()
        rows.value = SpeakingReplyRow(answer, AgentRunStatus.COMPLETED)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertTrue(session.state.replyComplete)
        if (!completeCueEarly) finishCue()
        looper.idle()
        assertEquals(listOfNotNull("Un instant.", remaining), audio.played)
        if (remaining != null) {
            assertEquals(token, owner.notification.value?.token)
            assertFalse(audio.closed)
            requireNotNull(audio.finished).invoke()
            looper.idle()
        }
        assertTrue(audio.closed)
        assertNull(owner.notification.value)
    }

    @Test fun aSecondRoomCannotAutoplayOrCaptureOverSpeechButAnExplicitReadCanTakeOver() {
        val (first, firstAudio) = start(11)
        val firstToken = requireNotNull(coordinator.notification.value).token
        coordinator.releaseScreenOwner(first)
        val audio = Output()
        lateinit var second: VoiceRuntime
        val session = VoiceSession(audio, { false }, { coordinator.observe(second, 22, it) })
        second = VoiceRuntime(mockk(), mockk(), null, mockk(), session, coordinator)
        session.access(true, true, true)
        session.autoContinue = true
        session.resume()
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        assertTrue(session.beginMessage("automatic"))
        session.reply("automatic", "This reply should remain visible in chat.")
        assertEquals("This reply should remain visible in chat.", session.state.reply)
        assertTrue(audio.played.isEmpty())
        assertEquals(firstToken, coordinator.notification.value?.token)
        assertTrue(coordinator.owns(first))

        val explicit = VoiceMessageIdentity(44, "selected")
        second.activeMessage.value = explicit
        second.manualMessage.value = true
        assertTrue(session.beginMessage(explicit.key, manual = true))
        session.reply(explicit.key, "Read this one.")
        assertEquals(listOf("Read this one."), audio.played)
        assertEquals(explicit, coordinator.notification.value?.message)
        coordinator.stop(firstToken)
        assertTrue(coordinator.owns(second))
        coordinator.stopCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(firstAudio.closed)
        session.close()
    }

    @Test fun reopeningPlaybackTargetsTheOriginalAnswerOnEveryTapEvenAfterNotificationReplacement() {
        val first = VoicePlaybackNotice("first", 11, VoiceMessageIdentity(5, "r"), VoicePhase.SPEAKING)
        val next = VoicePlaybackNotice("second", 22, VoiceMessageIdentity(9, "s"), VoicePhase.SPEAKING)
        val oldIntent = VoicePlaybackService.openReplyIntent(application, first)
        val newIntent = VoicePlaybackService.openReplyIntent(application, next)
        for ((notice, intent) in listOf(first to oldIntent, next to newIntent, first to oldIntent)) {
            intent.send()
            val delivered = shadowOf(application).nextStartedActivity
            assertEquals(MainActivity::class.java.name, delivered.component?.className)
            assertEquals(notice.roomId, delivered.getIntExtra(MainActivity.EXTRA_CHAT_ROOM_ID, 0))
            assertEquals(notice.message?.messageId, delivered.getIntExtra(MainActivity.EXTRA_TARGET_MESSAGE_ID, 0))
            assertEquals(notice.token, delivered.data?.lastPathSegment)
            assertFalse(delivered.hasExtra(MainActivity.EXTRA_NOTIFICATION_EVENT_ID))
        }
    }

    // Robolectric does not relay session activity or transport callbacks through the media binder.
    @Implements(MediaSession::class)
    class RecordingMediaSession : ShadowMediaSession() {
        companion object {
            var activity: PendingIntent? = null
            var controls: MediaSession.Callback? = null
        }

        @Implementation
        protected fun setSessionActivity(value: PendingIntent?) {
            activity = value
        }

        @Implementation
        protected fun setCallback(value: MediaSession.Callback?) {
            controls = value
        }
    }

    @Test
    @Config(shadows = [RecordingMediaSession::class])
    fun platformMediaControlsOpenAndStopOnlyTheirOwnReply() {
        val (first, _) = start(11)
        val firstStart = shadowOf(application).nextStartedService
        val service = Robolectric.buildService(VoicePlaybackService::class.java, firstStart).get()
        service.playback = coordinator
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(firstStart, 0, 1))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
        val firstNote = shadowOf(service).lastForegroundNotification
        val firstControls = requireNotNull(RecordingMediaSession.controls)
        assertEquals(firstNote.contentIntent, RecordingMediaSession.activity)
        coordinator.releaseScreenOwner(first)
        val (second, _) = start(22)
        service.onStartCommand(shadowOf(application).nextStartedService, 0, 2)
        val secondNote = shadowOf(service).lastForegroundNotification
        assertEquals(secondNote.contentIntent, RecordingMediaSession.activity)
        assertFalse(firstNote.contentIntent == secondNote.contentIntent)
        firstControls.onPause()
        firstControls.onStop()
        service.onStartCommand(shadowOf(firstNote.actions.single().actionIntent).savedIntent, 0, 3)
        assertTrue(coordinator.owns(second))
        requireNotNull(RecordingMediaSession.controls).onStop()
        assertNull(coordinator.notification.value)
        service.onDestroy()
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
