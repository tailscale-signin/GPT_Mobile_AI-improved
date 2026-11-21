package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object MaskedProviderCall : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<MaskedProviderCall>
}

internal suspend fun maskedProviderSession(
    store: PrivacyModeStore,
    profile: PlatformV2,
    turns: List<ConversationTurn>,
    open: suspend (List<ConversationTurn>, PlatformV2) -> AgentProviderSession
): AgentProviderSession {
    val state = store.snapshot()
    check(state.enabled)
    val mask = PrivateTextMask(state.categories, store.privateTerms())
    fun protect(message: MessageV2): MessageV2 {
        require(message.attachments.isEmpty())
        return MessageV2(content = mask.protect(message.content), platformType = message.platformType, id = message.id, chatId = message.chatId)
    }
    val maskedTurns = turns.map { it.copy(userMessage = protect(it.userMessage), assistantMessage = it.assistantMessage?.let(::protect)) }
    val maskedProfile = profile.copy(systemPrompt = profile.systemPrompt?.let(mask::protect), batchMode = false, batchApiUrl = null)
    val restore = mask.sealForResponse()
    val session = open(maskedTurns, maskedProfile)
    return object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            require(tools.isEmpty() && exchanges.isEmpty())
            check(store.snapshot() == state) { "Privacy settings changed. Resend with the current settings." }
            val answer = StringBuilder()
            session.streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> answer.append(event.text)
                    ProviderEvent.Completed -> {
                        if (answer.isNotEmpty()) emit(ProviderEvent.TextDelta(restore(answer.toString())))
                        emit(event)
                    }
                    else -> emit(event)
                }
            }
        }.flowOn(MaskedProviderCall)
    }
}
