package dev.chungjungsoo.gptmobile.service

import dev.chungjungsoo.gptmobile.data.model.Conversation
import dev.chungjungsoo.gptmobile.data.model.AutoContinueSettings
import dev.chungjungsoo.gptmobile.util.DebugUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OllamaAutoContinueService {
    private val TAG = "OllamaAutoContinueService"

    fun shouldAutoContinue(conversation: Conversation, messages: List<dev.chungjungsoo.gptmobile.data.model.Message>): Flow<Boolean> = flow {
        DebugUtils.logDebug("Checking if conversation ${conversation.id} should auto-continue")

        val settings = conversation.autoContinueSettings
        val maxTokens = settings.maxTokens
        val maxToolCalls = settings.maxToolCalls

        // Simple logic - in a real implementation this would check actual token usage and tool calls
        val shouldContinue = settings.isAutoContinueEnabled && 
            (messages.size >= maxToolCalls || messages.sumOf { it.content.length } >= maxTokens)

        DebugUtils.logDebug("Auto-continue decision for conversation ${conversation.id}: $shouldContinue")
        emit(shouldContinue)
    }
}