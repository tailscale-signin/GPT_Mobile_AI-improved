package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.presentation.service.VoicePlaybackService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class VoicePlaybackNotice(val token: String, val roomId: Int, val message: VoiceMessageIdentity?, val phase: VoicePhase)

/** Main-thread ownership of one admitted reply. The UI and service share its original audio queue. */
@Singleton
internal class VoiceBackgroundPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val persistence: Lazy<AgentPersistenceDao>,
    private val repository: Lazy<ChatRepository>
) {
    private class Owner(val runtime: VoiceRuntime, val key: String, val notice: VoicePlaybackNotice) {
        var detached = false
        var released = false
        var feed: Job? = null
        var flush: Job? = null
    }

    private var owner: Owner? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableNotice = MutableStateFlow<VoicePlaybackNotice?>(null)
    val notification = mutableNotice.asStateFlow()

    fun owns(runtime: VoiceRuntime) = owner?.runtime === runtime
    fun playingElsewhere(runtime: VoiceRuntime) = owner != null && !owns(runtime)

    fun observe(runtime: VoiceRuntime, roomId: Int, state: VoiceState) {
        val session = runtime.session
        val playing = state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.PREPARING
        val key = session.activeSpeechKey
        owner?.takeIf { it.runtime === runtime }?.let { active ->
            if (active.key == key && (playing || session.retainsReply)) {
                if (mutableNotice.value?.phase != state.phase) mutableNotice.value = active.notice.copy(phase = state.phase)
                return
            }
            release(active)
        }
        if (!playing || roomId <= 0 || key == null || !session.canStartPlaybackService) return
        stopCurrent()
        val message = runtime.activeMessage.value?.takeIf { it.key == key } ?: runtime.playback.active?.identity?.takeIf { it.key == key }
        val notice = VoicePlaybackNotice(UUID.randomUUID().toString(), roomId, message, state.phase)
        val active = Owner(runtime, key, notice)
        owner = active
        mutableNotice.value = notice
        try {
            VoicePlaybackService.start(context, notice)
            session.backgroundPlayback = true
        } catch (_: IllegalStateException) {
            release(active)
        } catch (_: SecurityException) {
            release(active)
        }
    }

    fun attach(runtime: VoiceRuntime) {
        val active = owner?.takeIf { it.runtime === runtime } ?: return
        active.detached = false
        active.feed?.cancel()
        active.feed = null
        active.flush?.cancel()
        active.flush = null
    }

    fun detach(runtime: VoiceRuntime) {
        val active = owner?.takeIf { it.runtime === runtime && !it.detached } ?: return
        active.detached = true
        val session = runtime.session
        session.autoContinue = false
        // Explicit replay already supplied its complete, possibly historical, revision.
        if (runtime.manualMessage.value || session.state.replyComplete) return
        val identity = runtime.playback.active?.identity?.takeIf { it.key == active.key }
        val fence = identity?.let(runtime.replyProjection::fenceFor)
        if (identity == null || fence == null) {
            stop(active.notice.token)
            return
        }
        fun flushPendingSpeech() {
            active.flush?.cancel()
            active.flush = if (session.nextSpeechFlushDelayMs() != null) scope.launch { session.awaitSpeechFlush() } else null
        }
        flushPendingSpeech()
        active.feed = scope.launch {
            try {
                persistence.get().observeSpeakingReply(active.notice.roomId, identity.messageId, identity.runId, fence)
                    .distinctUntilChanged().collect { row ->
                        if (owner !== active || !active.detached || session.state.replyComplete) return@collect
                        if (row == null) {
                            session.supersedeMessage(active.key)
                        } else if (row.status in AgentRunStatus.terminal) {
                            val terminal = repository.get().terminalVoiceReply(active.notice.roomId, identity.messageId, identity.runId, fence)
                            if (owner !== active || !active.detached) return@collect
                            if (terminal == null) {
                                session.supersedeMessage(active.key)
                            } else {
                                val failure = terminal.message.timeline.lastOrNull { it.profileFailure != null }?.profileFailure
                                    ?: terminal.run.terminalError?.let { ProfileFailureRecord.classify(it) }
                                session.reply(active.key, ConversationSubject.withoutMetadata(terminal.message.content), terminal.run.status != AgentRunStatus.COMPLETED, failure, terminal.run.status)
                            }
                        } else {
                            val text = ConversationSubject.withoutMetadata(row.content)
                            if (!session.state.reply.startsWith(text)) {
                                session.streamReply(active.key, text)
                                flushPendingSpeech()
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner === active && active.detached) {
                    session.terminalReadFailureReporter(active.key)(error)
                    stop(active.notice.token)
                }
            }
        }
    }

    fun releaseScreenOwner(runtime: VoiceRuntime) {
        val active = owner?.takeIf { it.runtime === runtime }
        if (active == null) {
            runtime.session.close()
        } else {
            active.released = true
            runtime.session.access(true, false, false)
            detach(runtime)
        }
    }

    fun stopCurrent() {
        owner?.let { stop(it.notice.token) }
    }

    fun stop(token: String?) {
        val active = owner?.takeIf { it.notice.token == token } ?: return
        release(active)
        active.runtime.session.pause("service_stop")
    }

    private fun release(active: Owner) {
        if (owner !== active) return
        owner = null
        active.runtime.session.backgroundPlayback = false
        active.feed?.cancel()
        active.flush?.cancel()
        mutableNotice.value = null
        if (active.released) main.post { active.runtime.session.close() }
    }
}
