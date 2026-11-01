package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val now: () -> Long = System::currentTimeMillis,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        exchanges.forEach { exchange -> exchange.results.forEach { result -> exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) } } }
        if (GitHubStatusRequest.matches(task)) {
            val (receipts, epoch) = evidence.snapshot()
            if (GitHubEvidencePolicy.status(receipts, now(), epoch) != null) {
                emitAll(publish(null))
                return@flow
            }
        }
        val draft = StringBuilder()
        var calls = false
        var completed = false
        var failed = false
        var providerFailure: ProviderEvent.Failed? = null
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
                    if (providerFailure == null) providerFailure = event
                }
                is ProviderEvent.CombinedAnswer -> failed = true
                else -> emit(event)
            }
        }
        if (failed || !completed) {
            // The runner owns retries and protocol repair. Do not turn a transient
            // failure into completion before that policy has had a chance to run.
            emit(providerFailure ?: ProviderEvent.Failed("GitHub response ended before verification. Receipts remain available.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        if (calls || (draft.isBlank() && tools.isNotEmpty())) {
            emit(ProviderEvent.Completed)
            return@flow
        }
        emitAll(publish(draft.toString()))
    }

    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = publish(null, terminalFallback = true)

    private fun publish(draft: String?, terminalFallback: Boolean = false): Flow<ProviderEvent> = flow {
        val publication = try {
            validatePublication()
            val (receipts, epoch) = evidence.snapshot()
            val request = if (draft == null) {
                null
            } else {
                progress("Verifying factual claims against exact tool receipts")
                withContext(computationDispatcher) { verificationRequest(draft, receipts) }
            }
            val status = if (GitHubStatusRequest.matches(task)) GitHubEvidencePolicy.status(receipts, now(), epoch) else null
            val (text, report) = status ?: if (request == null) partial(receipts, epoch) else extract(request, receipts, epoch)
            validatePublication()
            evidence.retainVerdict(report)
            text to (report["partial"] == kotlinx.serialization.json.JsonPrimitive(true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(ProviderEvent.Failed("GitHub answer withheld: ${failure.message.orEmpty().take(400)}. Not available / Not measured. Retained evidence can be inspected or refreshed.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        // Collector exceptions must propagate unchanged; they are not formatter failures.
        val (text, partial) = publication
        if (partial) {
            if (terminalFallback) emit(ProviderEvent.TextDelta(text))
            emit(
                ProviderEvent.Failed(
                    "GitHub response verification remained incomplete. Saved receipts and the task checkpoint are available for continuation.",
                    errorCode = "GITHUB_EVIDENCE_REQUIRED",
                    transportComplete = true
                )
            )
        } else {
            emit(ProviderEvent.TextDelta(text))
            emit(ProviderEvent.Completed)
        }
    }

    private suspend fun partial(receipts: Map<String, JsonObject>, epoch: Long): Pair<String, JsonObject> = withContext(computationDispatcher) {
        GitHubEvidencePolicy.partial(receipts, now(), epoch)
    }

    private suspend fun extract(request: String, receipts: Map<String, JsonObject>, epoch: Long): Pair<String, JsonObject> = try {
        withTimeoutOrNull(90_000) {
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
            withContext(computationDispatcher) {
                val answer = Json.parseToJsonElement(response.toString().trim()) as? JsonObject ?: error("Claim extraction was not structured")
                GitHubEvidencePolicy.render(answer, receipts, now(), epoch)
            }
        } ?: partial(receipts, epoch)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Permission, ledger and persistence failures remain outside this boundary.
        partial(receipts, epoch)
    }

    private fun verificationRequest(draft: String, receipts: Map<String, JsonObject>): String? {
        val limit = inputLimit()
        fun payload(catalog: List<kotlinx.serialization.json.JsonElement>) = buildJsonObject {
            put("task", task)
            put("candidate", draft)
            put("catalog", JsonArray(catalog))
            put("retained_receipts", receipts.size)
            put("omitted_from_context", receipts.size - catalog.size)
            put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
        }.toString()
        var request = payload(emptyList())
        if (TextTokenEstimate.estimate(request) > limit) return null
        val catalog = GitHubEvidencePolicy.catalog(receipts).reversed()
        var low = 0
        var high = catalog.size
        // Keep the newest fitting prefix in logarithmic serialization passes.
        while (low < high) {
            val count = low + (high - low + 1) / 2
            val candidate = payload(catalog.take(count))
            if (TextTokenEstimate.estimate(candidate) <= limit) {
                low = count
                request = candidate
            } else {
                high = count - 1
            }
        }
        return request.takeIf { TextTokenEstimate.estimate(it) <= inputLimit() }
    }
}
