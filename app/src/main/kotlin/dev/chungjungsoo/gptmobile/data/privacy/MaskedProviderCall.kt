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
import kotlinx.coroutines.currentCoroutineContext
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
    val protection = currentCoroutineContext()[PrivateProtectionContext]
    mask.seedIdentities(listOfNotNull(profile.systemPrompt) + turns.flatMap { listOfNotNull(it.userMessage.content, it.assistantMessage?.content) })
    fun protect(message: MessageV2): MessageV2 {
        require(message.attachments.isEmpty())
        val protected = mask.protectWithReceipt(message.content)
        protection?.input(message, protected.receipt)
        return MessageV2(content = protected.text, platformType = message.platformType, id = message.id, chatId = message.chatId)
    }
    val maskedTurns = turns.map { it.copy(userMessage = protect(it.userMessage), assistantMessage = it.assistantMessage?.let(::protect)) }
    val maskedProfile = profile.copy(systemPrompt = profile.systemPrompt?.let(mask::protect), batchMode = false, batchApiUrl = null)
    val restore = mask.sealWithReceipts()
    val session = open(maskedTurns, maskedProfile)
    return object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            require(tools.isEmpty() && exchanges.isEmpty())
            check(store.snapshot() == state) { "Privacy settings changed. Resend with the current settings." }
            protection?.flushInputs()
            check(store.snapshot() == state) { "Privacy settings changed. Resend with the current settings." }
            val authority = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]
            val privateAuthority = dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard(
                revision = "${authority?.revision.orEmpty()}:privacy-${state.revision}",
                authority = authority?.authority
            ) {
                check(store.snapshot() == state) { "Privacy settings changed. Resend with the current settings." }
                authority?.validate?.invoke()
            }
            val answer = StringBuilder()
            session.streamRound(emptyList(), emptyList()).flowOn(privateAuthority).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> answer.append(event.text)
                    ProviderEvent.Completed -> {
                        if (answer.isNotEmpty()) {
                            val restored = currentCoroutineContext()[PrivateToolReplyMask]?.restore(answer.toString(), restore) ?: restore(answer.toString())
                            protection?.restored(restored)
                            emit(ProviderEvent.TextDelta(restored.text))
                        }
                        emit(event)
                    }
                    else -> emit(event)
                }
            }
        }.flowOn(MaskedProviderCall)
    }
}
