package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Completion is gated; an optional fenced replacement preserves streaming drafts and revisions. */
internal class AnswerValidationSession(
    private val initial: AgentProviderSession,
    private val request: String,
    private val maximumCharacters: Int = 1_048_576,
    private val save: suspend (String, Boolean) -> Unit = { _, _ -> },
    private val replaceDraft: (suspend (String, String) -> Boolean)? = null,
    private val rewrite: suspend (String, List<String>, List<AgentToolExchange>) -> AgentProviderSession
) : AgentProviderSession {
    private var publishedDraft = ""
    override val handlesToolsInternally: Boolean get() = initial.handlesToolsInternally

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        suspend fun publish(text: String) {
            if (text.isBlank()) return
            if (publishedDraft.isEmpty()) {
                emit(ProviderEvent.TextDelta(text))
            } else if (publishedDraft != text) {
                check(replaceDraft?.invoke(dev.chungjungsoo.gptmobile.data.research.researchHash(publishedDraft), text) == true) { "ANSWER_REVISION_OWNER_CHANGED: The existing draft was preserved." }
            }
            publishedDraft = text
        }
        var active = initial
        var retained = ""
        for (attempt in 0..1) {
            val text = StringBuilder()
            var completed = false
            var failure: ProviderEvent.Failed? = null
            var toolRound = false
            var finish: String? = null
            try {
                active.streamRound(if (attempt == 0) tools else emptyList(), if (attempt == 0) exchanges else emptyList()).catch { error ->
                    if (error is CancellationException) throw error
                    emit(ProviderEvent.Failed("ANSWER_PROVIDER_FAILED: ${error.message.orEmpty()}. The draft is retained."))
                }.collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            check(text.length.toLong() + event.text.length <= (maximumCharacters.takeIf { it > 0 }?.toLong() ?: 1_048_576L)) { "Answer exceeds the safe validation buffer." }
                            text.append(event.text)
                            if (attempt == 0 && replaceDraft != null) {
                                emit(event)
                                publishedDraft += event.text
                            }
                        }
                        ProviderEvent.Completed -> completed = true
                        is ProviderEvent.Failed -> failure = event
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> {
                            toolRound = true
                            if (attempt == 0) emit(event) else failure = ProviderEvent.Failed("ANSWER_REWRITE_REQUESTED_TOOLS: No tool action was repeated.")
                        }
                        is ProviderEvent.Usage -> {
                            finish = event.finishReason ?: finish
                            emit(event)
                        }
                        else -> emit(event)
                    }
                }
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { save(text.toString().ifBlank { retained }, false) }
                throw cancelled
            }
            if (toolRound && attempt == 0) {
                if (replaceDraft == null && text.isNotEmpty()) emit(ProviderEvent.TextDelta(text.toString()))
                failure?.let { emit(it) }
                if (completed && failure == null) emit(ProviderEvent.Completed)
                return@flow
            }
            val candidate = text.toString()
            if (candidate.isNotBlank()) retained = candidate
            val defects = AnswerCompletionChecks.failures(request, candidate, finish)
            if (failure == null && completed && defects.isEmpty()) {
                save(candidate, true)
                if (attempt > 0 || replaceDraft == null) publish(candidate)
                emit(ProviderEvent.Completed)
                return@flow
            }
            // Provider/access/queue failures are not writing defects and must not enqueue a replacement.
            if (attempt == 1 || failure != null || !completed || candidate.isBlank()) {
                save(retained, false)
                if (attempt > 0 || replaceDraft == null) publish(retained)
                emit(failure ?: ProviderEvent.Failed("ANSWER_REQUEST_UNSATISFIED: ${defects.ifEmpty { listOf("GENERATION_INCOMPLETE") }.joinToString()}. The draft is retained; completion was not validated."))
                return@flow
            }
            save(candidate, false)
            emit(ProviderEvent.Notice("Checking the answer found ${defects.joinToString()}. Rewriting once to satisfy the request.", false))
            active = try {
                rewrite(candidate, defects, exchanges)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (replaceDraft == null) publish(candidate)
                emit(ProviderEvent.Failed("ANSWER_REWRITE_UNAVAILABLE: ${error.message.orEmpty()}. The draft is retained."))
                return@flow
            }
        }
    }
}

/** Conservative observable defects, not a universal coherence or factual-accuracy classifier. */
internal object AnswerCompletionChecks {
    fun failures(request: String, answer: String, finishReason: String? = null): List<String> = buildList {
        if (answer.isBlank()) {
            add("EMPTY_ANSWER")
            return@buildList
        }
        val policy = CombinedAnswerPolicy.from(request)
        addAll(policy.violations(answer).filterNot { it == "DUPLICATE_SENTENCES" })
        if (finishReason in setOf("length", "max_tokens", "MAX_TOKENS")) add("OUTPUT_TRUNCATED")
        val sentences = CombinedContentCoverage.sentences(answer).map { it.lowercase().replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 25 }
        if (sentences.size >= 4 && sentences.distinct().size * 2 <= sentences.size) add("SUBSTANTIAL_REPETITION")
        val words = Regex("[\\p{L}\\p{N}]+").findAll(answer.lowercase()).map { it.value }.toList()
        if (words.size >= 60 && words.distinct().size * 5 < words.size) add("SUBSTANTIAL_REPETITION")
        val proseTask = policy.task?.action == "HISTORY" || Regex("(?i)\\b(?:essay|prose|paragraph)\\b").containsMatchIn(request)
        val last = answer.trimEnd().trimEnd('"', '”', '\'').trimEnd()
        if (proseTask && words.size >= 40 && !last.endsWith(".") && !last.endsWith("!") && !last.endsWith("?") && !last.endsWith("]") && !last.endsWith("```")) add("UNFINISHED_PROSE")
        if (answer.count { it == '\uFFFD' || it.isISOControl() && !it.isWhitespace() } * 20 > answer.length) add("CORRUPT_TEXT")
    }.distinct()
}
