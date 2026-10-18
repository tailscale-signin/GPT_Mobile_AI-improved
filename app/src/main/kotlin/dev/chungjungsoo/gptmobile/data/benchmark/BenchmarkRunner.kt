package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

data class BenchmarkCase(
    val id: String,
    val label: String,
    val category: String,
    val prompt: String,
    val expected: String? = null,
    val warmup: Boolean = false,
    val workload: String = "short",
    val seed: Int = 0,
    val benchmarkReview: Boolean = false,
    val calculatorExpression: String? = null,
    val imageCode: String? = null,
    val attachments: List<dev.chungjungsoo.gptmobile.data.model.ChatAttachment> = emptyList()
) {
    val requestCategory: String get() = if (category == IntelligenceBenchmark.CATEGORY && !id.startsWith("intelligence-batch-")) "intelligence_individual" else category
}

fun benchmarkSuite(mode: BenchmarkMode, contextTokens: Int? = null): List<BenchmarkCase> = BenchmarkWorkloadPlanner.adapt(
    when (mode) {
        BenchmarkMode.DELEGATION -> delegationBenchmarkSuite()
        BenchmarkMode.QUICK -> BenchmarkSuiteRegistry.quick
        BenchmarkMode.DIAGNOSTIC -> BenchmarkCapabilitySuite.cases
        else -> BenchmarkSuiteRegistry.full
    },
    contextTokens
)

/** Historical suites keep their original manifest; old measurements never join a new plan. */
internal fun benchmarkSuiteForRun(run: BenchmarkRun): List<BenchmarkCase> {
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 2) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK, paired = true)
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 1) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK)
    if (run.suiteVersion == 6) return BenchmarkWorkloadPlanner.adapt(BenchmarkSuiteRegistry.historical(run.mode), run.fixtureContextTokens)
    if (run.suiteVersion >= 7 || run.mode == BenchmarkMode.DELEGATION) return benchmarkSuite(run.mode, run.fixtureContextTokens)
    val legacy = if (run.mode == BenchmarkMode.QUICK) {
        listOf(
            BenchmarkCase("speed-0", "Delivery", "speed", "Write about 120 words explaining how rain forms, in plain English. No heading."),
            BenchmarkCase("instruction", "Exact instruction", "task", "Reply with exactly BENCHMARK_READY and nothing else.", "BENCHMARK_READY"),
            BenchmarkCase("json", "Structured JSON", "json", "Return only this JSON object: {\"status\":\"ready\",\"count\":3,\"items\":[\"red\",\"green\",\"blue\"]}"),
            BenchmarkCase("arithmetic", "Arithmetic", "task", "What is 17 * 23 + 9? Reply with only the integer.", "400"),
            BenchmarkCase("context", "Conversation recall", "task", "What was the parcel code I gave you? Reply with only the code.", "ORCHID-742"),
            BenchmarkCase("tool", "Tool readiness", "tools", "Call benchmark_lookup with key=\"parcel\". Reply with only the code returned by that tool. Do not guess.")
        )
    } else {
        BenchmarkSuiteRegistry.legacyStandard(run.mode == BenchmarkMode.AGENT).filter { run.suiteVersion >= 5 || it.category != IntelligenceBenchmark.CATEGORY }
    }
    return BenchmarkWorkloadPlanner.adapt(legacy, run.fixtureContextTokens)
}

/** Runs only synthetic prompts and an in-memory fixture. No chat history, MCP or device tools. */
class BenchmarkRunner(
    private val openSession: suspend (List<ConversationTurn>, List<AgentTool>) -> AgentProviderSession,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMs: Long = 90_000
) {
    suspend fun run(test: BenchmarkCase, supportsTools: Boolean): BenchmarkSample {
        val first = runOnce(test, supportsTools, timeoutMs)
        val remaining = timeoutMs - first.durationMs
        val transient = first.error.orEmpty().lowercase().let { message ->
            listOf("connection abort", "connection reset", "broken pipe", "unexpected end of stream").any { it in message }
        }
        if (first.outcome != BenchmarkOutcome.ERROR || !transient || first.outputCharacters > 0 || first.outputTokens > 0 || first.toolCalls > 0 || remaining <= 0) return first
        // One new session, within the original deadline, only before any text/tool activity.
        val retry = runOnce(test, supportsTools, remaining)
        return retry.copy(
            durationMs = first.durationMs + retry.durationMs,
            firstTextMs = retry.firstTextMs?.plus(first.durationMs),
            lastTextMs = retry.lastTextMs?.plus(first.durationMs),
            inputTokens = first.inputTokens + retry.inputTokens,
            outputTokens = first.outputTokens + retry.outputTokens,
            estimatedTokens = first.estimatedTokens || retry.estimatedTokens,
            reconnectAttempts = 1,
            retryDurationMs = first.durationMs + retry.retryDurationMs
        )
    }

    private suspend fun runOnce(test: BenchmarkCase, supportsTools: Boolean, limitMs: Long): BenchmarkSample {
        if (test.imageCode != null && test.attachments.none { it.mimeType.startsWith("image/") }) {
            return BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, error = "Required diagnostic image was not prepared; no provider request sent.", failureCategory = "FIXTURE_INPUT", workload = test.workload)
        }
        if (test.category == "tools" && !supportsTools) {
            return BenchmarkSample(
                test.id,
                test.label,
                test.category,
                BenchmarkOutcome.UNSUPPORTED,
                error = "This model does not advertise tool support; excluded from the rating.",
                failureCategory = "UNSUPPORTED",
                tokenBasis = ReferenceTextTokenizer.VERSION,
                warmup = test.warmup,
                workload = test.workload
            )
        }
        val started = now()
        val text = StringBuilder()
        val segments = mutableListOf<dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics>()
        val rounds = mutableListOf<BenchmarkRoundMetrics>()
        var configured: ProviderEvent.RequestConfigured? = null
        var first: Long? = null
        var firstChunkCharacters = 0
        var lastChunk: Long? = null
        var longestGap: Long? = null
        var chunks = 0
        var inputTokens = 0
        var roundInputTokens = 0
        var tokens = 0
        var estimated = false
        var roundTokens: Int? = null
        var roundStart = 0
        var roundAccounted = true
        var providerFailure: String? = null
        var finishReason: String? = null
        var effectiveSettingsConfirmed = false
        var retryDurationMs = 0L
        var reconnectAttempts = 0
        var requestedOutputTokens: Int? = null
        var effectiveRequestOutputTokens: Int? = null
        var requestedReasoningEffort: String? = null
        var nativeMetrics: dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics? = null
        val calls = linkedMapOf<String, ProviderEvent.ToolCall>()
        val successful = mutableSetOf<String>()
        val code = test.expected.takeIf { test.calculatorExpression != null } ?: "PKG-${UUID.randomUUID().toString().take(8)}"
        val scenario = BenchmarkToolScenario(test, code)
        val expectedErrors = mutableSetOf<String>()
        fun sample(outcome: BenchmarkOutcome, error: String? = null) = BenchmarkSample(
            test.id, test.label, test.category, outcome, (now() - started).coerceAtLeast(0), first,
            tokens + if (roundAccounted) 0 else roundTokens ?: ((text.length - roundStart + 3) / 4),
            estimated || (!roundAccounted && roundTokens == null), text.length, chunks, longestGap, calls.size, successful.size,
            text.take(1000).toString(), error, lastChunk?.let { it - started }, nativeMetrics, inputTokens + if (roundAccounted) 0 else roundInputTokens,
            referenceTokens = ReferenceTextTokenizer.starts(text.toString()).size,
            referenceDecodeTokens = ReferenceTextTokenizer.starts(text.toString()).count { it >= firstChunkCharacters },
            tokenBasis = ReferenceTextTokenizer.VERSION,
            warmup = test.warmup,
            workload = test.workload,
            firstChunkCharacters = firstChunkCharacters,
            finishReason = finishReason,
            retryDurationMs = retryDurationMs,
            reconnectAttempts = reconnectAttempts,
            requestedOutputTokens = requestedOutputTokens,
            effectiveRequestOutputTokens = effectiveRequestOutputTokens,
            requestedReasoningEffort = requestedReasoningEffort,
            completionKind = when {
                finishReason in setOf("length", "MAX_TOKENS") && test.category == "speed" -> "benchmark_cutoff"
                finishReason in setOf("length", "MAX_TOKENS") -> "output_limit"
                outcome == BenchmarkOutcome.ERROR -> "interrupted_or_failed"
                outcome == BenchmarkOutcome.PASSED || outcome == BenchmarkOutcome.FAILED -> "complete"
                else -> "unknown"
            },
            effectiveSettingsConfirmed = effectiveSettingsConfirmed,
            localSegments = segments.toList(),
            rounds = rounds.toList(),
            inputImageCount = configured?.inputImageCount,
            inputModalities = configured?.inputModalities.orEmpty(),
            languageBackend = configured?.languageBackend,
            visionBackend = configured?.visionBackend,
            finalResponseFormat = configured?.finalResponseFormat
        )
        try {
            val finished = withTimeoutOrNull(limitMs) {
                val turns = buildList {
                    if (test.id == "context") add(ConversationTurn(message("Remember this parcel code: ORCHID-742."), message("I will remember ORCHID-742."), false))
                    add(ConversationTurn(message(test.prompt).copy(attachments = test.attachments), null, true))
                }
                val tools = if (test.category == "tools") scenario.tools else emptyList()
                val session = openSession(turns, tools)
                val exchanges = mutableListOf<AgentToolExchange>()
                var done = false
                repeat(3) {
                    if (done) return@repeat
                    val roundCalls = linkedMapOf<String, ProviderEvent.ToolCall>()
                    roundStart = text.length
                    roundTokens = null
                    roundInputTokens = 0
                    roundAccounted = false
                    var completed = false
                    finishReason = null
                    session.streamRound(tools.map { it.definition }, exchanges.toList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> if (event.text.isNotEmpty()) {
                                val time = now()
                                if (first == null) {
                                    first = (time - started).coerceAtLeast(0)
                                    firstChunkCharacters = event.text.length
                                }
                                lastChunk?.let { longestGap = maxOf(longestGap ?: 0, time - it) }
                                lastChunk = time
                                chunks++
                                check(text.length + event.text.length <= 16_384) { "Response exceeded the benchmark size limit" }
                                text.append(event.text)
                            }
                            is ProviderEvent.ToolCall -> {
                                check(test.category == "tools") { "Unexpected tool call in a text test" }
                                check(event.callId !in calls) { "Duplicate completed tool call ID" }
                                calls[event.callId] = event
                                roundCalls[event.callId] = event
                                check(calls.size <= 3) { "Tool call limit reached" }
                            }
                            is ProviderEvent.ToolResult -> {
                                if (scenario.valid(event.call) && !event.result.isError) successful.add(event.call.callId)
                                if (scenario.expectedError(event.call, event.result)) expectedErrors.add(event.call.callId)
                            }
                            is ProviderEvent.RequestConfigured -> {
                                configured = event
                                requestedOutputTokens = event.requestedOutputTokens ?: event.configuredProfileOutputTokens
                                effectiveRequestOutputTokens = event.effectiveOutputTokens
                                requestedReasoningEffort = event.reasoningEffort
                            }
                            is ProviderEvent.Usage -> {
                                event.retryDurationMs?.let { retryDurationMs = maxOf(retryDurationMs, it) }
                                event.retryAttempts?.let { reconnectAttempts = maxOf(reconnectAttempts, it) }
                                if (event.finishReason != null) finishReason = event.finishReason
                                effectiveSettingsConfirmed = effectiveSettingsConfirmed || event.effectiveSettingsConfirmed
                                event.inputTokens?.takeIf { it >= 0 }?.let { count ->
                                    roundInputTokens = if (event.cumulative) maxOf(roundInputTokens, count) else roundInputTokens + count
                                }
                                event.outputTokens?.takeIf { it >= 0 }?.let { count ->
                                    roundTokens = if (event.cumulative) maxOf(roundTokens ?: 0, count) else (roundTokens ?: 0) + count
                                }
                            }
                            is ProviderEvent.LocalMetrics -> {
                                segments.add(event.metrics)
                                nativeMetrics = event.metrics.native?.takeIf { it.isValid }
                            }
                            is ProviderEvent.Failed -> providerFailure = event.message
                            ProviderEvent.Completed -> completed = true
                            else -> Unit
                        }
                    }
                    val throughputCutoff = test.category == "speed" && finishReason in setOf("length", "MAX_TOKENS") && text.length >= 100 && calls.isEmpty()
                    providerFailure?.takeUnless { throughputCutoff }?.let { error(it) }
                    check(completed || throughputCutoff) { "Provider ended without a completion event" }
                    if (roundTokens == null) estimated = true
                    tokens += roundTokens ?: ((text.length - roundStart + 3) / 4)
                    inputTokens += roundInputTokens
                    rounds.add(BenchmarkRoundMetrics(rounds.size, roundInputTokens, roundTokens ?: ((text.length - roundStart + 3) / 4), roundTokens == null, finishReason, completed))
                    roundAccounted = true
                    if (roundCalls.isEmpty() || session.handlesToolsInternally) {
                        done = true
                    } else {
                        val results = roundCalls.values.map { call ->
                            val tool = scenario.tools.singleOrNull { it.definition.name == call.name }
                            val result = if (tool != null && scenario.valid(call)) {
                                tool.execute(call.callId, call.arguments)
                            } else {
                                AgentToolResult(call.callId, ToolResultContent.Text("Unknown fixture tool or invalid arguments"), true)
                            }
                            if (!result.isError) successful.add(call.callId)
                            if (scenario.expectedError(call, result)) expectedErrors.add(call.callId)
                            result
                        }
                        exchanges.add(AgentToolExchange(roundCalls.values.toList(), results))
                        // Preserve unsolicited prose so strict final-output checks can detect it.
                        if (text.isNotEmpty()) text.append('\n')
                    }
                }
                check(done) { "Tool round limit reached" }
                check(text.isNotBlank()) { "Provider returned no answer" }
                true
            }
            if (finished != true) {
                return sample(
                    BenchmarkOutcome.TIMED_OUT,
                    "Exceeded ${limitMs / 1000}s remaining per-test limit"
                ).copy(failureCategory = "TIMEOUT")
            }
            val answer = text.toString().trim()
            val intelligenceGrade = if (test.id.startsWith("intelligence-batch-")) IntelligenceBenchmark.evaluateBatch(test, answer) else null
            val timedFixture = test.category == "speed" && listOf("short", "medium", "long").any { test.id.startsWith("speed-$it-") }
            val referenceUnits = ReferenceTextTokenizer.starts(answer).size
            val insufficientTiming = timedFixture && referenceUnits < BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS
            val passed = when (test.category) {
                IntelligenceBenchmark.CATEGORY -> if (test.id.startsWith("intelligence-batch-")) intelligenceGrade!!.answers.values.all { it } else IntelligenceBenchmark.matches(answer, checkNotNull(test.expected))
                "speed" -> answer.length >= 100 && calls.isEmpty() && (!test.id.startsWith("speed-short-") && !test.id.startsWith("speed-medium-") && !test.id.startsWith("speed-long-") || !insufficientTiming)
                "json" -> runCatching { Json.parseToJsonElement(answer) == Json.parseToJsonElement(test.expected ?: "{\"status\":\"ready\",\"count\":3,\"items\":[\"red\",\"green\",\"blue\"]}") }.getOrDefault(false)
                "tools" -> scenario.passed(calls.values.toList(), successful, expectedErrors) && answer == code
                else -> answer == (
                    test.expected ?: when (test.id) {
                        "instruction" -> "BENCHMARK_READY"
                        "arithmetic" -> "400"
                        "context" -> "ORCHID-742"
                        else -> ""
                    }
                    )
            }
            val result = sample(
                if (passed) BenchmarkOutcome.PASSED else BenchmarkOutcome.FAILED,
                when {
                    insufficientTiming -> "Insufficient timing sample: $referenceUnits reference units produced; ${BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS} required."
                    intelligenceGrade != null -> "${intelligenceGrade.answers.values.count { it }}/${intelligenceGrade.answers.size} correct; raw JSON compliance: ${intelligenceGrade.formatCompliant}; parseable: ${intelligenceGrade.parseable}."
                    passed -> null
                    else -> "Response did not meet the fixture's expected result."
                }
            ).copy(
                intelligenceAnswers = intelligenceGrade?.answers.orEmpty(),
                intelligenceFormatCompliant = intelligenceGrade?.formatCompliant,
                intelligenceParseable = intelligenceGrade?.parseable,
                intelligenceFixtureVersion = intelligenceGrade?.fixtureVersion,
                intelligenceCorrection = intelligenceGrade?.correction,
                timingEligible = if (timedFixture) !insufficientTiming && passed else null
            )
            return if (passed) result else result.copy(failureCategory = if (insufficientTiming) "INSUFFICIENT_TIMING_SAMPLE" else "MODEL_QUALITY")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val qnn = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<dev.chungjungsoo.gptmobile.data.localruntime.QnnRuntimeFailure>()
                .firstOrNull()
            val detail = error.message.orEmpty()
            val reportedQnnStage = Regex("QNN ([A-Z_]+) failed \\[").find(detail)?.groupValues?.getOrNull(1)
            val category = when {
                qnn != null || reportedQnnStage != null -> "INIT"
                detail.contains("context", ignoreCase = true) && detail.contains("token", ignoreCase = true) -> "CONTEXT"
                detail.contains("timeout", ignoreCase = true) -> "TIMEOUT"
                detail.contains("provider", ignoreCase = true) || detail.contains("http", ignoreCase = true) -> "PROVIDER"
                detail.contains("disabled", ignoreCase = true) || detail.contains("unsupported", ignoreCase = true) || detail.contains("chat-only support", ignoreCase = true) -> "UNSUPPORTED"
                else -> "UNKNOWN"
            }
            val outcome = when {
                category == "UNSUPPORTED" -> BenchmarkOutcome.UNSUPPORTED
                detail.contains("free allowance", true) || detail.contains("429") || detail.contains("quota", true) || detail.contains("cooldown", true) -> BenchmarkOutcome.QUOTA_BLOCKED
                detail.contains("POLICY_RESTRICTED", true) || detail.contains("training violation", true) -> BenchmarkOutcome.POLICY_BLOCKED
                finishReason in setOf("length", "MAX_TOKENS") -> BenchmarkOutcome.PARTIAL
                else -> BenchmarkOutcome.ERROR
            }
            return sample(outcome, DiagnosticRedactor.redact(detail.ifBlank { "Benchmark request failed" }).take(500)).copy(
                failureCategory = category,
                runtimeStage = qnn?.stage?.name ?: reportedQnnStage
            )
        }
    }
}

private fun message(text: String) = MessageV2(content = text, platformType = null)

/** Infrastructure errors are not model-quality failures, even in full-suite mode. */
internal fun benchmarkInfrastructureFailure(sample: BenchmarkSample): Boolean {
    if (sample.outcome in setOf(BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED) || sample.failureCategory == "INIT") return true
    if (sample.outcome == BenchmarkOutcome.PASSED) return false
    val detail = listOfNotNull(sample.error).plus(sample.delegation?.diagnosticEvents.orEmpty().map { it.message }).joinToString("\n")
    return listOf(
        "unable to resolve host", "unknownhostexception", "no route to host", "connection refused",
        "sslhandshakeexception", "certificate verification failed", "http 401", "http 403", "invalid api key",
        "input tensor not found", "engine_initialization", "missing_asset", "not downloaded", "http 429", "rate limit",
        "native inference worker stopped", "interrupted inside native code", "quarantined",
        "unsupported backend", "memory_admission_rejected", "failed to load the local inference engine"
    ).any { detail.contains(it, ignoreCase = true) }
}

/** Preserve completed samples, but do not send the rest of a suite to a blocked provider. */
suspend fun runBenchmarkSuite(
    suite: List<BenchmarkCase>,
    runCase: suspend (Int, BenchmarkCase) -> BenchmarkSample,
    onSample: suspend (BenchmarkSample) -> Unit,
    stopOnError: Boolean = true
): String? {
    for ((index, test) in suite.withIndex()) {
        val sample = runCase(index, test)
        onSample(sample)
        val reason = when {
            benchmarkInfrastructureFailure(sample) -> "Provider or runtime is unavailable; remaining cases were skipped. ${sample.error.orEmpty()}"
            stopOnError && sample.outcome in setOf(BenchmarkOutcome.ERROR, BenchmarkOutcome.TIMED_OUT) && sample.failureCategory != "CONTEXT" -> sample.error ?: "The provider could not complete the benchmark request."
            else -> null
        }
        if (reason != null) {
            for (remaining in suite.drop(index + 1)) {
                onSample(BenchmarkSample(remaining.id, remaining.label, remaining.category, BenchmarkOutcome.SKIPPED, error = reason, failureCategory = "SKIPPED_DEPENDENCY", tokenBasis = ReferenceTextTokenizer.VERSION, warmup = remaining.warmup, workload = remaining.workload))
            }
            return reason
        }
    }
    return null
}
