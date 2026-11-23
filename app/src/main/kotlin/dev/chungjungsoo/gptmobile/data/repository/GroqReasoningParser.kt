package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.voice.ReasoningTagScanner

private const val GROQ_THINK_OPEN = "<think>"
private const val SUBJECT_PREFIX = "<!-- chat_subject:"

/**
 * Compatible providers use a separate reasoning field or a leading <think> protocol block.
 * Once answer prose/code starts, literal tags belong to that answer and are never stripped.
 * An interrupted reasoning block remains reasoning; flushing cannot promote it into speech.
 */
internal class GroqReasoningParser {
    private enum class Phase { LEADING, THINKING, ANSWER }
    private val pendingContent = StringBuilder()
    private val pendingThought = StringBuilder()
    private var phase = Phase.LEADING
    private var reasoningTags: ReasoningTagScanner? = null
    private var trimLeadingWhitespaceOnNextContent = false

    fun append(contentChunk: String? = null, reasoningChunk: String? = null): List<ApiState> = buildList {
        reasoningChunk?.takeIf(String::isNotEmpty)?.let { add(ApiState.Thinking(it)) }
        if (!contentChunk.isNullOrEmpty()) {
            pendingContent.append(contentChunk)
            addAll(drain(finalFlush = false))
        }
    }

    fun flush(): List<ApiState> = drain(finalFlush = true)

    private fun drain(finalFlush: Boolean): List<ApiState> = buildList {
        while (pendingContent.isNotEmpty() || finalFlush && phase == Phase.THINKING) {
            when (phase) {
                Phase.ANSWER -> {
                    addAll(emitVisibleText(pendingContent.toString()))
                    pendingContent.clear()
                }
                Phase.LEADING -> {
                    val leading = pendingContent.toString()
                    val text = leading.trimStart()
                    if (text.isEmpty()) {
                        if (finalFlush) {
                            addAll(emitVisibleText(leading))
                            pendingContent.clear()
                        }
                        break
                    }
                    if (GROQ_THINK_OPEN.startsWith(text, ignoreCase = true)) {
                        if (text.length < GROQ_THINK_OPEN.length) {
                            // A truncated protocol delimiter is not an answer. Keep it pending
                            // until completion; quoted/escaped/code examples enter ANSWER first.
                            if (finalFlush) pendingContent.clear()
                            break
                        }
                    }
                    if (text.startsWith(GROQ_THINK_OPEN, ignoreCase = true)) {
                        pendingContent.delete(0, leading.length - text.length + GROQ_THINK_OPEN.length)
                        reasoningTags = ReasoningTagScanner("think", exactTags = true)
                        phase = Phase.THINKING
                        continue
                    }
                    // Subject metadata may precede a protocol block. Preserve that comment
                    // for its owner without classifying it as the beginning of answer prose.
                    if (SUBJECT_PREFIX.startsWith(text) && !finalFlush) break
                    if (text.startsWith(SUBJECT_PREFIX)) {
                        val end = text.indexOf("-->", SUBJECT_PREFIX.length)
                        if (end < 0 && !finalFlush && text.length <= 4096) break
                        if (end >= 0) {
                            val consumed = leading.length - text.length + end + 3
                            addAll(emitVisibleText(leading.substring(0, consumed)))
                            pendingContent.delete(0, consumed)
                            continue
                        }
                    }
                    phase = Phase.ANSWER
                }
                Phase.THINKING -> {
                    val text = pendingContent.toString()
                    val scan = requireNotNull(reasoningTags).scan(text, final = finalFlush)
                    val closingIndex = scan.closingStart
                    if (closingIndex != null) {
                        pendingThought.append(text.substring(0, closingIndex))
                        pendingContent.delete(0, scan.consumed)
                        pendingThought.toString().trim().takeIf(String::isNotEmpty)?.let { add(ApiState.Thinking(it)) }
                        pendingThought.clear()
                        reasoningTags = null
                        phase = Phase.LEADING
                        trimLeadingWhitespaceOnNextContent = true
                        continue
                    }
                    val safeLength = scan.consumed
                    if (safeLength > 0) {
                        pendingThought.append(text.substring(0, safeLength))
                        pendingContent.delete(0, safeLength)
                    }
                    // Bound retained reasoning while preserving its separate channel.
                    if (finalFlush || pendingThought.length >= 8192) {
                        pendingThought.toString().takeIf(String::isNotBlank)?.let { add(ApiState.Thinking(it)) }
                        pendingThought.clear()
                    }
                    if (finalFlush) {
                        phase = Phase.LEADING
                        reasoningTags = null
                    }
                    break
                }
            }
        }
    }

    private fun emitVisibleText(text: String): List<ApiState> {
        if (text.isEmpty()) return emptyList()
        val visibleText = if (trimLeadingWhitespaceOnNextContent) {
            text.trimStart().also { if (it.isNotEmpty()) trimLeadingWhitespaceOnNextContent = false }
        } else {
            text
        }
        return if (visibleText.isEmpty()) emptyList() else listOf(ApiState.Success(visibleText, OutputLineage(OutputOrigin.MODEL_TEXT)))
    }
}
