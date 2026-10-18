package dev.chungjungsoo.gptmobile.data.conversation

import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser

/** One answer predicate for delivery, persistence, history and rendering. Raw output stays intact. */
object AnswerProjection {
    fun text(raw: String): String = ConversationSubject.withoutMetadata(ThinkingParser.extractThinking(raw).response)
    fun hasAnswer(raw: String): Boolean = text(raw).isNotBlank()
}
