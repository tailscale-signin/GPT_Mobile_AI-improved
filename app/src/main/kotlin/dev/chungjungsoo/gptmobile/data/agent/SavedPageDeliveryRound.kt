package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** A bounded primary-model read; its provisional notes are checkpointed, never published as an answer. */
internal class SavedPageDeliveryRound private constructor(private val expectedProjection: String) : AbstractCoroutineContextElement(Key) {
    @Volatile private var freshRequestCompleted = false

    companion object Key : CoroutineContext.Key<SavedPageDeliveryRound> {
        /** Called only around actual provider adapters, never around cached/decorated sessions. */
        fun providerRound(exchanges: List<AgentToolExchange>, events: Flow<ProviderEvent>): Flow<ProviderEvent> = flow {
            val read = currentCoroutineContext()[Key]
            if (read != null || SavedPageReading.present(exchanges)) dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.requireUnprotectedTransport()
            var failed = false
            events.collect { event ->
                if (event is ProviderEvent.Failed) failed = true
                if (event == ProviderEvent.Completed && !failed && read != null && read.expectedProjection == SavedPageReading.projectionDigest(exchanges)) read.freshRequestCompleted = true
                emit(event)
            }
        }

        fun read(session: AgentProviderSession, exchanges: List<AgentToolExchange>, maxNoteBytes: Int = 1536, retainNotes: (String) -> Unit = {}): Flow<ProviderEvent> = flow {
            require(maxNoteBytes in 1..1536)
            val read = SavedPageDeliveryRound(SavedPageReading.projectionDigest(exchanges))
            val notes = StringBuilder()
            var failed = false
            var completed = false
            var reportedFailure = false
            session.streamRound(emptyList(), exchanges).flowOn(read).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        if (event.lineage.origin !in setOf(OutputOrigin.MODEL_TEXT, OutputOrigin.UNKNOWN)) {
                            failed = true
                        } else {
                            if (notes.length.toLong() + event.text.length <= maxNoteBytes && !failed) {
                                notes.append(event.text)
                                if (notes.toString().toByteArray(Charsets.UTF_8).size > maxNoteBytes) failed = true
                            } else {
                                failed = true
                            }
                        }
                    }
                    is ProviderEvent.Failed -> {
                        failed = true
                        reportedFailure = true
                        emit(event)
                    }
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult, is ProviderEvent.CombinedAnswer -> failed = true
                    ProviderEvent.Completed -> completed = true
                    is ProviderEvent.Usage, is ProviderEvent.RequestConfigured -> emit(event)
                    else -> Unit
                }
            }
            if (!failed && completed && read.freshRequestCompleted && notes.isNotBlank()) {
                retainNotes(notes.toString())
                emit(ProviderEvent.Completed)
            } else if (!reportedFailure) {
                emit(ProviderEvent.Failed("The selected model did not complete a fresh saved-page read with bounded reading notes. Its pending pages remain checkpointed.", errorCode = "REPLAY_EVIDENCE_REQUIRED"))
            }
        }
    }
}
