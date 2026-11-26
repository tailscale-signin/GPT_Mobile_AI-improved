package dev.chungjungsoo.gptmobile.data.context

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.TokenWindowChatMemory
import dev.langchain4j.model.TokenCountEstimator

/** Budget complete turns as atomic entries so user/assistant/attachment context stays together. */
internal object MemoryTokenWindow {
    private val terms = Regex("""[\p{L}\p{N}_-]{4,64}""")
    private fun keywords(text: String): Set<String> {
        fun excerpt(value: String) = terms.findAll(value.lowercase()).map { it.value }.filterNot { it in commonWords }.distinct().take(64).toSet()
        if (text.length <= 2048) return excerpt(text)
        return excerpt(text.take(1024)) + excerpt(text.takeLast(1024))
    }

    private val commonWords = setOf("that", "this", "with", "what", "have", "from", "were", "would", "please", "could", "about", "there", "your", "which")

    /** Retain the previous request and a clearly partial response when a follow-up cannot fit the whole turn. */
    fun continuityTurn(history: List<ConversationTurn>, available: Int, question: String, cost: (ConversationTurn) -> Int): ConversationTurn? {
        val previous = history.lastOrNull() ?: return null
        if (available <= 0 || !Regex("(?i)\\b(?:summari[sz]e|summary|summaries|previous|above|these|those|again|continue|solutions)\\b").containsMatchIn(question)) return null
        if (cost(previous) <= available) return previous
        val answer = previous.assistantMessage?.takeIf { it.content.isNotBlank() && it.attachments.isEmpty() } ?: return null
        val label = "[Partial excerpt of the previous response. Omitted text remains in the saved conversation; this is not complete evidence.]\n"
        fun candidate(characters: Int): ConversationTurn {
            val front = answer.content.take((characters + 1) / 2).dropLastWhile { Character.isHighSurrogate(it) }
            val end = answer.content.takeLast(characters / 2).dropWhile { Character.isLowSurrogate(it) }
            return previous.copy(assistantMessage = answer.copy(content = label + front + "\n[... omitted ...]\n" + end))
        }
        if (cost(candidate(0)) > available) return null
        var low = 0
        var high = answer.content.length
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (cost(candidate(middle)) <= available) low = middle else high = middle - 1
        }
        return candidate(low)
    }

    /** Spend history headroom on complete, chronological turns within this conversation. */
    fun forQuestion(history: List<ConversationTurn>, available: Int, question: String, cost: (ConversationTurn) -> Int): List<ConversationTurn> {
        if (available <= 0 || history.isEmpty()) return emptyList()
        val costs = history.map(cost)
        if (costs.sumOf { it.toLong() } <= available) return history
        var remaining = available
        val selected = BooleanArray(history.size)
        fun keep(index: Int) {
            if (!selected[index] && costs[index] in 0..remaining) {
                selected[index] = true
                remaining -= costs[index]
            }
        }
        keep(history.lastIndex)
        keep(0)
        val query = keywords(question)
        if (query.isNotEmpty() && remaining > 0) {
            // Inspect only bounded excerpts. Reserve one slot for a relevant older exchange.
            val match = history.indices.reversed().asSequence().filter { !selected[it] && costs[it] <= remaining }
                .map { index ->
                    val turn = history[index]
                    val overlap = (keywords(turn.userMessage.content) + keywords(turn.assistantMessage?.content.orEmpty())).count { it in query }
                    index to overlap
                }.filter { it.second > 0 }.maxByOrNull { it.second }
            match?.let { keep(it.first) }
        }
        for (index in history.indices.reversed()) keep(index)
        return history.filterIndexed { index, _ -> selected[index] }
    }

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
