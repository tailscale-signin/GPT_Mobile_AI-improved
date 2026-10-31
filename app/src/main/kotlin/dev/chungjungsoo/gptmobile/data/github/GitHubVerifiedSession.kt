package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All providers keep drafts private. Only code-rendered receipt facts and labeled interpretations are published. */
internal class GitHubVerifiedSession(
    private val delegate: AgentProviderSession,
    private val evidence: GitHubEvidenceBook,
    private val task: String,
    private val inputLimit: () -> Int,
    private val openVerifier: suspend (String) -> AgentProviderSession,
    private val validatePublication: suspend () -> Unit,
    private val progress: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        exchanges.forEach { exchange -> exchange.results.forEach { result -> exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) } } }
        val draft = StringBuilder()
        var calls = false
        var completed = false
        var failed = false
        delegate.streamRound(tools, exchanges).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> {
                    check(draft.length.toLong() + event.text.length <= 1_048_576) { "GitHub verification draft exceeds capacity" }
                    draft.append(event.text)
                }
                is ProviderEvent.ToolCall -> {
                    calls = true
                    emit(event)
                }
                is ProviderEvent.ToolResult -> {
                    evidence.observe(event.call, event.result)
                    emit(event)
                }
                is ProviderEvent.Completed -> completed = true
                is ProviderEvent.Failed -> {
                    failed = true
                    emit(event)
                }
                is ProviderEvent.CombinedAnswer -> failed = true
                else -> emit(event)
            }
        }
        if (failed || !completed) {
            if (!failed) emit(ProviderEvent.Failed("GitHub response ended before verification. Receipts remain available.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        if (calls) {
            emit(ProviderEvent.Completed)
            return@flow
        }
        try {
            validatePublication()
            val (receipts, epoch) = evidence.snapshot()
            progress("Verifying factual claims against exact tool receipts")
            val catalog = GitHubEvidencePolicy.catalog(receipts).reversed().toMutableList()
            fun payload() = buildJsonObject {
                put("task", task)
                put("candidate", draft.toString())
                put("catalog", JsonArray(catalog))
                put("retained_receipts", receipts.size)
                put("omitted_from_context", receipts.size - catalog.size)
                put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
            }.toString()
            var request = payload()
            while (TextTokenEstimate.estimate(request) > inputLimit() && catalog.isNotEmpty()) {
                catalog.removeAt(catalog.lastIndex)
                request = payload()
            }
            check(TextTokenEstimate.estimate(request) <= inputLimit()) { "Task and draft exceed the verification context; narrow the task before publication" }
            val response = StringBuilder()
            var verifiedComplete = false
            openVerifier(request).streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        check(response.length.toLong() + event.text.length <= 131_072) { "Claim extraction exceeded its output bound" }
                        response.append(event.text)
                    }
                    is ProviderEvent.Completed -> verifiedComplete = true
                    is ProviderEvent.Failed -> error("Claim extraction did not complete")
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Evidence formatting attempted an operation")
                    else -> Unit
                }
            }
            check(verifiedComplete) { "Claim extraction was interrupted" }
            val answer = Json.parseToJsonElement(response.toString().trim()) as? JsonObject ?: error("Claim extraction was not structured")
            val (text, report) = GitHubEvidencePolicy.render(answer, receipts, now(), epoch)
            validatePublication()
            evidence.retainVerdict(report)
            emit(ProviderEvent.TextDelta(text))
            emit(ProviderEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(ProviderEvent.Failed("GitHub answer withheld: ${failure.message.orEmpty().take(400)}. Not available / Not measured. Retained evidence can be inspected or refreshed.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
        }
    }
}
