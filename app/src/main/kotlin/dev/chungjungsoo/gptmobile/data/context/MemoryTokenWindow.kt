package dev.chungjungsoo.gptmobile.data.context

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.TokenWindowChatMemory
import dev.langchain4j.model.TokenCountEstimator

/** Budget complete turns as atomic entries so user/assistant/attachment context stays together. */
internal object MemoryTokenWindow {
    fun select(history: List<ConversationTurn>, availableTokens: Int, cost: (ConversationTurn) -> Int): List<ConversationTurn> {
        if (availableTokens <= 0 || history.isEmpty()) return emptyList()
        val costs = history.mapIndexed { index, turn -> index.toString() to cost(turn) }.toMap()
        val estimator = object : TokenCountEstimator {
            override fun estimateTokenCountInText(text: String) = costs[text] ?: ContextBudgetService.estimate(text)
            override fun estimateTokenCountInMessage(message: ChatMessage) = estimateTokenCountInText((message as UserMessage).singleText())
            override fun estimateTokenCountInMessages(messages: Iterable<ChatMessage>) = messages.sumOf(::estimateTokenCountInMessage)
        }
        val window = TokenWindowChatMemory.builder().maxTokens(availableTokens, estimator).build()
        // set() avoids repeatedly recounting a large restored conversation.
        window.set(history.indices.map { UserMessage.from(it.toString()) })
        return window.messages().map { history[(it as UserMessage).singleText().toInt()] }
    }
}
