package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/** One state per collected run, including all tool rounds. Never retain the full response. */
internal class GenerationSafety(private val characterLimit: Int, private val detectRepetition: Boolean) {
    private var characters = 0L
    private val tail = StringBuilder()
    private var sinceCheck = 0
    private var stopped = false

    fun apply(source: Flow<ProviderEvent>): Flow<ProviderEvent> = source.transformWhile { event ->
        if (stopped) return@transformWhile false
        val text = when (event) {
            is ProviderEvent.TextDelta -> event.text
            is ProviderEvent.ThinkingDelta -> event.text
            else -> null
        }
        if (text == null) {
            emit(event)
            return@transformWhile true
        }
        val available = if (characterLimit > 0) (characterLimit.toLong() - characters).coerceAtLeast(0) else Long.MAX_VALUE
        // Do not split a UTF-16 surrogate pair at a safety boundary.
        var accepted = minOf(text.length.toLong(), available).toInt()
        if (accepted in 1 until text.length && text[accepted - 1].isHighSurrogate() && text[accepted].isLowSurrogate()) accepted--
        val retained = text.take(accepted)
        characters += retained.length
        if (retained.isNotEmpty()) {
            emit(
                when (event) {
                    is ProviderEvent.TextDelta -> event.copy(text = retained)
                    is ProviderEvent.ThinkingDelta -> event.copy(text = retained)
                    else -> event
                }
            )
        }
        val repetition = detectRepetition && event is ProviderEvent.TextDelta && repeated(retained)
        if (accepted < text.length || repetition) {
            stopped = true
            val reason = if (repetition) "sustained exact repetition" else "the configured generated-character safeguard"
            emit(ProviderEvent.Failed("GENERATION_SAFETY_STOP: Stopped for $reason. Partial text and completed evidence are retained. Adjust Generation Safeguards or explicitly continue; no completed action was repeated."))
        }
        !stopped
    }

    private fun repeated(text: String): Boolean {
        // Bounded tail and checks; a huge upstream chunk cannot allocate an unbounded repeat buffer.
        tail.append(text.takeLast(32_768))
        if (tail.length > 32_768) tail.delete(0, tail.length - 32_768)
        sinceCheck = (sinceCheck.toLong() + text.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (sinceCheck < 2048) return false
        sinceCheck = 0
        for (period in listOf(256, 512, 1024, 2048, 4096)) {
            if (tail.length < period * 8) continue
            val start = tail.length - period * 8
            val block = tail.substring(tail.length - period)
            if (block.count { !it.isWhitespace() } < 64) continue
            if ((start until tail.length - period).all { tail[it] == block[(it - start) % period] }) return true
        }
        return false
    }
}

internal fun AgentProviderSession.withGenerationSafety(safety: GenerationSafety): AgentProviderSession = object : AgentProviderSession {
    override val handlesToolsInternally = this@withGenerationSafety.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        safety.apply(this@withGenerationSafety.streamRound(tools, exchanges)).collect { emit(it) }
    }
}
