package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** An intermediate primary-model read. Its provisional answer and proposed calls never execute. */
internal class SavedPageDeliveryRound : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SavedPageDeliveryRound> {
        fun read(session: AgentProviderSession, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            var modelOutput = false
            var failed = false
            session.streamRound(emptyList(), exchanges).flowOn(SavedPageDeliveryRound()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> if (event.text.isNotBlank() && event.lineage.origin in setOf(OutputOrigin.MODEL_TEXT, OutputOrigin.UNKNOWN)) modelOutput = true
                    is ProviderEvent.Failed -> {
                        failed = true
                        emit(event)
                    }
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> if (!failed) {
                        failed = true
                        emit(ProviderEvent.Failed("The saved-page read requested a tool. Its pending pages remain checkpointed.", errorCode = "REPLAY_EVIDENCE_REQUIRED"))
                    }
                    ProviderEvent.Completed -> if (!failed) {
                        if (modelOutput) emit(event) else emit(ProviderEvent.Failed("The selected model did not acknowledge its saved-page input.", errorCode = "REPLAY_EVIDENCE_REQUIRED"))
                    }
                    is ProviderEvent.Usage, is ProviderEvent.RequestConfigured -> emit(event)
                    else -> Unit
                }
            }
        }
    }
}
