package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsageCounter
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
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
    contextTokens,
    reserveTokens = 1280
)

/** Historical suites keep their original manifest; old measurements never join a new plan. */
internal fun benchmarkSuiteForRun(run: BenchmarkRun): List<BenchmarkCase> {
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 2) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK, paired = true)
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 1) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK)
    if (run.suiteVersion == 6) return BenchmarkWorkloadPlanner.adapt(BenchmarkSuiteRegistry.historical(run.mode), run.fixtureContextTokens)
    if (run.suiteVersion >= 7) return BenchmarkWorkloadPlanner.adapt(benchmarkSuiteForVersion(run.suiteVersion, run.mode), run.fixtureContextTokens, if (run.suiteVersion >= 8) 1280 else 1024)
    if (run.mode == BenchmarkMode.DELEGATION) return benchmarkSuite(run.mode, run.fixtureContextTokens)
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
    private val timeoutMs: Long = STANDARD_BENCHMARK_DEADLINE_MS,
    private val allowReconnect: Boolean = false,
    private val onInterrupted: (BenchmarkSample) -> Unit = {}
) {
    suspend fun run(test: BenchmarkCase, supportsTools: Boolean): BenchmarkSample {
        val first = runOnce(test, supportsTools, timeoutMs)
        if (!allowReconnect) return first
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
        if (test.category == "json" || test.id.startsWith("intelligence-batch-")) {
            val expected = test.expected ?: "{\"status\":\"ready\",\"count\":3,\"items\":[\"red\",\"green\",\"blue\"]}"
            if (runCatching { Json.parseToJsonElement(expected) }.isFailure) return BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, error = "FIXTURE_INVALID: Expected JSON cannot be parsed; no model request was sent.", failureCategory = "FIXTURE_INVALID", requestDispatched = false, workload = test.workload)
        }
        if (test.imageCode != null && test.attachments.none { it.mimeType.startsWith("image/") }) {
            return BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, error = "Required diagnostic image was not prepared; no provider request sent.", failureCategory = "FIXTURE_INPUT", requestDispatched = false, workload = test.workload)
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
                requestDispatched = false,
                workload = test.workload
            )
        }
        val started = now()
        val phases = BenchmarkPhaseTimeline(started, now)
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
        var roundInputUsage = ProviderUsageCounter()
        var roundOutputUsage = ProviderUsageCounter()
        var roundReasoningUsage = ProviderUsageCounter()
        var tokens = 0
        var estimated = false
        var roundTokens: Int? = null
        var roundStart = 0
        var roundAccounted = true
        var providerFailure: ProviderEvent.Failed? = null
        var providerFailureStage: String? = null
        var finishReason: String? = null
        var effectiveSettingsConfirmed = false
        var terminalReceived = false
        var observedAnswerLength = 0
        var truncatedAnswerHash: String? = null
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
        fun roundInputSource() = if (roundInputUsage.value != null) roundInputUsage.source else "unknown"
        fun roundOutputSource() = if (roundOutputUsage.value != null) {
            roundOutputUsage.source
        } else if (text.length > roundStart) {
            "estimated_characters"
        } else {
            "unknown"
        }
        fun roundOutputConfidence() = if (roundOutputUsage.value != null) {
            benchmarkUsageConfidence(roundOutputUsage)
        } else if (text.length > roundStart) {
            "estimated"
        } else {
            "unknown"
        }
        fun sample(outcome: BenchmarkOutcome, error: String? = null): BenchmarkSample {
            val measuredAt = now()
            val intervals = phases.snapshot(measuredAt)
            return BenchmarkSample(
                test.id, test.label, test.category, outcome, (measuredAt - started).coerceAtLeast(0), first,
                tokens + if (roundAccounted) 0 else roundTokens ?: ((text.length - roundStart + 3) / 4),
                estimated || rounds.isEmpty() && roundAccounted || (!roundAccounted && !measuredUsageSource(roundOutputUsage.source, roundOutputUsage.phase)), text.length, chunks, longestGap, calls.size, successful.size,
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
                finalResponseFormat = configured?.finalResponseFormat,
                requestDispatched = if (chunks > 0 || calls.isNotEmpty() || terminalReceived || rounds.isNotEmpty()) true else null,
                terminalReceived = terminalReceived,
                canonicalAnswer = text.toString(), answerHash = truncatedAnswerHash ?: benchmarkHash(text.toString()), answerLength = maxOf(observedAnswerLength, text.length), answerTruncated = truncatedAnswerHash != null,
                fixtureHash = benchmarkHash(test.id + "\u0000" + test.prompt + "\u0000" + test.expected),
                validatorVersion = "benchmark-validator-v3",
                deliveryMode = if (chunks > 1) {
                    "streaming"
                } else if (chunks == 1) {
                    "buffered"
                } else {
                    "no_visible_answer"
                },
                inputTokenSource = benchmarkCombinedTokenSource(rounds.map { it.inputTokenSource } + if (roundAccounted) emptyList() else listOf(roundInputSource())),
                outputTokenSource = benchmarkCombinedTokenSource(rounds.map { it.outputTokenSource } + if (roundAccounted) emptyList() else listOf(roundOutputSource())),
                inputTokenConfidence = benchmarkCombinedConfidence(rounds.map { it.inputTokenConfidence } + if (roundAccounted) emptyList() else listOf(benchmarkUsageConfidence(roundInputUsage))),
                outputTokenConfidence = benchmarkCombinedConfidence(rounds.map { it.outputTokenConfidence } + if (roundAccounted) emptyList() else listOf(roundOutputConfidence())),
                phaseIntervals = intervals,
                phaseElapsedMs = phases.elapsedByPhase(measuredAt),
                phaseIntervalsTruncated = phases.truncated,
                reasoningTokens = (rounds.mapNotNull { it.reasoningTokens } + if (roundAccounted) emptyList() else listOfNotNull(roundReasoningUsage.value?.toInt())).takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                reasoningTokenSource = benchmarkCombinedTokenSource(rounds.map { it.reasoningTokenSource } + if (roundAccounted) emptyList() else listOf(if (roundReasoningUsage.value == null) "unknown" else roundReasoningUsage.source)),
                reasoningTokenConfidence = benchmarkCombinedConfidence(rounds.map { it.reasoningTokenConfidence } + if (roundAccounted) emptyList() else listOf(benchmarkUsageConfidence(roundReasoningUsage)))
            )
        }
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
                    roundInputUsage = ProviderUsageCounter()
                    roundOutputUsage = ProviderUsageCounter()
                    roundReasoningUsage = ProviderUsageCounter()
                    roundAccounted = false
                    var completed = false
                    finishReason = null
                    phases.transition("provider_request", rounds.size)
                    session.streamRound(tools.map { it.definition }, exchanges.toList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> if (event.text.isNotEmpty()) {
                                val time = now()
                                phases.transition("visible_output", rounds.size)
                                if (first == null) {
                                    first = (time - started).coerceAtLeast(0)
                                    firstChunkCharacters = event.text.length
                                }
                                lastChunk?.let { longestGap = maxOf(longestGap ?: 0, time - it) }
                                lastChunk = time
                                chunks++
                                observedAnswerLength += event.text.length
                                if (text.length + event.text.length > 16_384) {
                                    truncatedAnswerHash = benchmarkHash(text.toString() + event.text)
                                    text.append(event.text.take((16_384 - text.length).coerceAtLeast(0)))
                                    error("Response exceeded the benchmark size limit; retained answer is truncated.")
                                }
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
                                roundInputUsage.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
                                roundOutputUsage.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
                                roundReasoningUsage.accept(event.reasoningTokens, event.cumulative, event.outputSource, event.phase)
                                roundInputTokens = roundInputUsage.value?.toInt() ?: 0
                                roundTokens = roundOutputUsage.value?.toInt()
                            }
                            is ProviderEvent.PhaseChanged -> phases.transition(event.phase.name.lowercase(), rounds.size, event.nativeProgress)
                            is ProviderEvent.LocalMetrics -> {
                                segments.add(event.metrics)
                                nativeMetrics = event.metrics.native?.takeIf { it.isValid }
                            }
                            is ProviderEvent.Failed -> {
                                providerFailure = event
                                providerFailureStage = phases.phase
                            }
                            ProviderEvent.Completed -> {
                                completed = true
                                terminalReceived = true
                            }
                            else -> Unit
                        }
                    }
                    val throughputCutoff = test.category == "speed" && finishReason in setOf("length", "MAX_TOKENS") && text.length >= 100 && calls.isEmpty()
                    providerFailure?.let {
                        if (it.errorCode == "POLICY_BLOCKED") throw BenchmarkPolicyBlockedException(it.message)
                        if (!throughputCutoff) error(it.message)
                    }
                    check(completed || throughputCutoff) { "Provider ended without a completion event" }
                    val outputMeasured = measuredUsageSource(roundOutputUsage.source, roundOutputUsage.phase)
                    if (!outputMeasured) estimated = true
                    tokens += roundTokens ?: ((text.length - roundStart + 3) / 4)
                    inputTokens += roundInputTokens
                    rounds.add(BenchmarkRoundMetrics(rounds.size, roundInputTokens, roundTokens ?: ((text.length - roundStart + 3) / 4), !outputMeasured, finishReason, completed, roundInputSource(), roundOutputSource(), benchmarkUsageConfidence(roundInputUsage), roundOutputConfidence(), roundReasoningUsage.value?.toInt(), if (roundReasoningUsage.value == null) "unknown" else roundReasoningUsage.source, benchmarkUsageConfidence(roundReasoningUsage)))
                    roundAccounted = true
                    if (roundCalls.isEmpty() || session.handlesToolsInternally) {
                        done = true
                    } else {
                        phases.transition("fixture_tools", rounds.size - 1)
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
                check(text.isNotBlank()) { "NO_VISIBLE_ANSWER: Provider finished without a visible final answer." }
                true
            }
            if (finished != true) {
                return sample(
                    BenchmarkOutcome.TIMED_OUT,
                    "Exceeded ${limitMs / 1000}s remaining per-test limit"
                ).copy(failureCategory = "REQUEST_DEADLINE_EXCEEDED", failure = BenchmarkFailure("REQUEST_DEADLINE_EXCEEDED", phases.phase, "Exceeded ${limitMs / 1000}s per-test deadline", owner = "request", retryable = true), runtimeStage = phases.phase, timingEligible = false, throughputExclusion = "REQUEST_DEADLINE_EXCEEDED")
            }
            phases.transition("validation")
            val answer = text.toString().trim()
            check(!invalidBenchmarkEnvelope(answer)) { "OUTPUT_PROTOCOL_INVALID: Provider protocol metadata is not a benchmark answer." }
            val intelligenceGrade = if (test.id.startsWith("intelligence-batch-")) IntelligenceBenchmark.evaluateBatch(test, answer) else null
            val timedFixture = test.category == "speed" && listOf("short", "medium", "long").any { test.id.startsWith("speed-$it-") }
            val referenceUnits = ReferenceTextTokenizer.starts(answer).size
            val insufficientTiming = timedFixture && referenceUnits < BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS
            val passed = when (test.category) {
                IntelligenceBenchmark.CATEGORY -> if (test.id.startsWith("intelligence-batch-")) intelligenceGrade!!.answers.values.all { it } else IntelligenceBenchmark.matches(answer, checkNotNull(test.expected))
                "speed" -> validBenchmarkSpeedAnswer(test, answer) && calls.isEmpty() && (!test.id.startsWith("speed-short-") && !test.id.startsWith("speed-medium-") && !test.id.startsWith("speed-long-") || !insufficientTiming)
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
                timingEligible = if (test.category == "speed") !insufficientTiming && passed && chunks > 1 && first != null && (lastChunk?.minus(started) ?: 0) > first!! else null,
                throughputExclusion = when {
                    test.category != "speed" -> null
                    !passed -> "TASK_NOT_PASSED"
                    chunks <= 1 || first == null || (lastChunk?.minus(started) ?: 0) <= first!! -> "NO_MEASURABLE_DECODE_INTERVAL"
                    insufficientTiming -> "INSUFFICIENT_OUTPUT"
                    else -> null
                }
            )
            return if (passed) result else result.copy(failureCategory = if (insufficientTiming) "INSUFFICIENT_TIMING_SAMPLE" else "MODEL_QUALITY")
        } catch (cancel: CancellationException) {
            onInterrupted(sample(BenchmarkOutcome.CANCELED, "Request interrupted; observed output is retained.").copy(timingEligible = false, throughputExclusion = "INTERRUPTED"))
            throw cancel
        } catch (error: Exception) {
            val qnn = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<dev.chungjungsoo.gptmobile.data.localruntime.QnnRuntimeFailure>()
                .firstOrNull()
            val detail = error.message.orEmpty()
            val reportedQnnStage = Regex("QNN ([A-Z_]+) failed \\[").find(detail)?.groupValues?.getOrNull(1)
            val classified = benchmarkFailure(error, qnn?.stage?.name?.lowercase() ?: reportedQnnStage?.lowercase() ?: providerFailureStage ?: phases.phase)
            val outcome = when {
                classified.code == "NO_VISIBLE_ANSWER" || text.isBlank() && finishReason in setOf("length", "MAX_TOKENS") -> BenchmarkOutcome.ERROR
                finishReason in setOf("length", "MAX_TOKENS") && text.isNotBlank() -> BenchmarkOutcome.PARTIAL
                else -> classified.outcome()
            }
            val failure = if (text.isBlank() && finishReason in setOf("length", "MAX_TOKENS")) classified.copy(code = "NO_VISIBLE_ANSWER") else classified
            return sample(outcome, failure.message).copy(
                failureCategory = failure.code,
                failure = failure,
                runtimeStage = failure.stage,
                requestDispatched = when {
                    chunks > 0 || calls.isNotEmpty() || rounds.isNotEmpty() -> true
                    failure.code in setOf("RESOURCE_ADMISSION", "NATIVE_QUARANTINED", "COOLDOWN_ACTIVE", "POLICY_BLOCKED") -> false
                    else -> null
                },
                timingEligible = false,
                throughputExclusion = failure.code
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
            sample.executionFailure() && sample.failureCategory != "FIXTURE_INVALID" -> sample.error ?: "The provider could not complete the benchmark request."
            else -> null
        }
        if (reason != null) {
            for (remaining in suite.drop(index + 1)) {
                onSample(BenchmarkSample(remaining.id, remaining.label, remaining.category, BenchmarkOutcome.SKIPPED, error = reason, failureCategory = "SKIPPED_DEPENDENCY", tokenBasis = ReferenceTextTokenizer.VERSION, warmup = remaining.warmup, workload = remaining.workload, requestDispatched = false))
            }
            return reason
        }
    }
    return null
}

/** Detect transport wrappers without interpreting arbitrary task JSON as a provider envelope. */
internal fun invalidBenchmarkEnvelope(answer: String): Boolean {
    if (Regex("(?is)^\\s*(?:<!doctype\\s+html|<html|<head|<body)").containsMatchIn(answer)) return true
    val value = runCatching { Json.parseToJsonElement(answer) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return false
    val objectType = (value["object"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val role = (value["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return objectType.startsWith("chat.completion") || (value["choices"] is kotlinx.serialization.json.JsonArray && ("usage" in value || "model" in value)) || role == "assistant" && ("reasoning" in value || "content" in value)
}

internal fun validBenchmarkSpeedAnswer(test: BenchmarkCase, answer: String): Boolean {
    if (invalidBenchmarkEnvelope(answer) || answer.length < 100) return false
    val words = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*").findAll(answer).count()
    if ("180 to 220" in test.prompt && (words !in 180..220 || listOf("evaporation", "condensation", "cloud", "precipitation").any { !answer.contains(it, true) })) return false
    if ("about 120 words" in test.prompt && words !in 90..150) return false
    return true
}
