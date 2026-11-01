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
        if (GitHubStatusRequest.matches(task) && tools.isNotEmpty()) {
            val (receipts, _) = evidence.snapshot()
            val next = GitHubStatusRequest.nextCall(tools, receipts)
            if (next != null) {
                emit(next)
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (receipts.isNotEmpty()) {
                emitAll(publish(null))
                return@flow
            }
        }
        if (GitHubStatusRequest.matches(task)) {
            val (receipts, epoch) = evidence.snapshot()
            val hasStatusSnapshot = receipts.values.any { receipt ->
                (receipt["arguments"] as? JsonObject)?.get("action") == kotlinx.serialization.json.JsonPrimitive("status")
            }
            if (hasStatusSnapshot && GitHubEvidencePolicy.status(receipts, now(), epoch) != null) {
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
            if (!calls && providerFailure?.errorCode?.startsWith("GITHUB_REVIEW_") == true) {
                emitAll(publish(null, failureCode = providerFailure.errorCode))
                return@flow
            }
            emit(providerFailure ?: ProviderEvent.Failed("GitHub response ended before verification. Receipts remain available.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        if (calls || (draft.isBlank() && tools.isNotEmpty())) {
            emit(ProviderEvent.Completed)
            return@flow
        }
        emitAll(publish(draft.toString()))
    }

    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> =
        if (dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery.isConnectionUnavailable(failure.message)) {
            // A failed connection is not a claim-verification failure. Keep its actionable cause.
            kotlinx.coroutines.flow.flowOf(failure.copy(errorCode = "LOCAL_SERVICE_UNREACHABLE"))
        } else {
            publish(null, terminalFallback = true, failureCode = failure.errorCode)
        }

    private fun publish(draft: String?, terminalFallback: Boolean = false, failureCode: String? = null): Flow<ProviderEvent> = flow {
        val publication = try {
            validatePublication()
            val (receipts, epoch) = evidence.snapshot()
            val status = if (GitHubStatusRequest.matches(task)) GitHubEvidencePolicy.status(receipts, now(), epoch) else null
            val coverage = if (GitHubTaskCoverage.matches(task)) {
                withContext(computationDispatcher) {
                    GitHubEvidencePolicy.testing(receipts, now(), epoch, task)
                }
            } else {
                null
            }
            val request = if (draft == null || status != null || coverage != null) {
                null
            } else {
                progress("Verifying factual claims against exact tool receipts")
                withContext(computationDispatcher) { verificationRequest(draft, receipts) }
            }
            val (text, report) = status ?: coverage ?: if (request == null) partial(receipts, epoch, failureCode ?: "GITHUB_EXTRACTION_UNAVAILABLE") else extract(request, receipts, epoch)
            validatePublication()
            val partial = report["partial"] == kotlinx.serialization.json.JsonPrimitive(true)
            check(receipts.isNotEmpty() || !partial) { "No retained GitHub evidence is available for publication" }
            val retained = if (partial && terminalFallback) JsonObject(report + ("resume_required" to kotlinx.serialization.json.JsonPrimitive(true))) else report
            evidence.retainVerdict(retained)
            (if (partial && terminalFallback) "**GitHub verification incomplete.**\n\n$text" else text) to partial
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(ProviderEvent.Failed("GitHub answer withheld: ${failure.message.orEmpty().take(400)}. Not available / Not measured. Retained evidence can be inspected or refreshed.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        // Collector exceptions must propagate unchanged; they are not formatter failures.
        val (text, partial) = publication
        if (partial && terminalFallback) {
            emit(ProviderEvent.TextDelta(text))
            emit(
                ProviderEvent.Failed(
                    "GitHub response verification remained incomplete. Saved receipts and the task checkpoint are available for continuation.",
                    errorCode = failureCode ?: "GITHUB_EVIDENCE_REQUIRED",
                    transportComplete = true
                )
            )
        } else {
            emit(ProviderEvent.TextDelta(text))
            emit(ProviderEvent.Completed)
        }
    }

    private suspend fun partial(receipts: Map<String, JsonObject>, epoch: Long, failureCode: String? = null): Pair<String, JsonObject> = withContext(computationDispatcher) {
        GitHubEvidencePolicy.partial(receipts, now(), epoch, task, failureCode)
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
                GitHubEvidencePolicy.render(answer, receipts, now(), epoch, task)
            }
        } ?: partial(receipts, epoch, "GITHUB_EXTRACTION_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Permission, ledger and persistence failures remain outside this boundary.
        partial(receipts, epoch, "GITHUB_EXTRACTION_INVALID_RESPONSE")
    }

    private fun verificationRequest(draft: String, receipts: Map<String, JsonObject>): String? {
        val hardLimit = inputLimit()
        fun payload(catalog: List<kotlinx.serialization.json.JsonElement>) = buildJsonObject {
            put("task", task)
            put("candidate", draft)
            put("catalog", JsonArray(catalog))
            put("retained_receipts", receipts.size)
            put("omitted_from_context", receipts.size - catalog.size)
            put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
        }.toString()
        var request = payload(emptyList())
        val baseTokens = TextTokenEstimate.estimate(request)
        if (baseTokens > hardLimit) return null
        val limit = minOf(hardLimit, maxOf(6_000, baseTokens + 2_048))
        val catalog = GitHubVerificationCatalog.select(kotlinx.serialization.json.JsonArray(GitHubEvidencePolicy.catalog(receipts).reversed()), "$task\n$draft")
        val selected = mutableListOf<kotlinx.serialization.json.JsonElement>()
        // A large receipt must not prevent later, smaller evidence from being considered.
        for (entry in catalog) {
            val candidate = payload(selected + entry)
            if (TextTokenEstimate.estimate(candidate) <= limit) {
                selected += entry
                request = candidate
            }
        }
        return request.takeIf { TextTokenEstimate.estimate(it) <= inputLimit() }
    }
}
