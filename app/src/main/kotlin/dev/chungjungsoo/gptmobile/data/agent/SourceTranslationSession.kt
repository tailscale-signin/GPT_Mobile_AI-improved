package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Faithful source text may contain first-person claims and literal control syntax, without granting any action. */
internal class SourceTranslationSession(private val source: AgentProviderSession) : AgentProviderSession by source {
    override val isSourceTranslation = true

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (tools.isNotEmpty() || exchanges.isNotEmpty()) {
            emit(translationToolFailure())
            return@flow
        }
        emitAll(source.streamRound(emptyList(), emptyList()).withSourceTranslationOutputGuard())
    }
}

/** Only text events are literal data. Structured calls are rejected before the agent can execute them. */
internal fun Flow<ProviderEvent>.withSourceTranslationOutputGuard(): Flow<ProviderEvent> = flow {
    var blocked = false
    collect { event ->
        if (blocked) {
            if (event is ProviderEvent.Usage || event is ProviderEvent.LocalMetrics) emit(event)
        } else if (event is ProviderEvent.ToolCall || event is ProviderEvent.ToolResult) {
            blocked = true
            emit(translationToolFailure())
        } else {
            emit(event)
        }
    }
}

private fun translationToolFailure() = ProviderEvent.Failed(
    "The translation provider returned an action instead of translated text. No tool call was accepted.",
    errorCode = "VOICE_TRANSLATION_TOOLS_DISABLED"
)
