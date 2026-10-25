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

/** One isolated attempt with small collection, validation and accounting methods. */
internal class BenchmarkCaseExecution(
    private val openSession: suspend (List<ConversationTurn>, List<AgentTool>) -> AgentProviderSession,
    private val now: () -> Long,
    private val onInterrupted: (BenchmarkSample) -> Unit,
    private val observeRuntime: () -> dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt?,
    private val test: BenchmarkCase
) {
    private val started = now()
    private val phases = BenchmarkPhaseTimeline(started, now)
    private val text = StringBuilder()
    private val segments = mutableListOf<dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics>()
    private val rounds = mutableListOf<BenchmarkRoundMetrics>()
    private val executionReceipts = linkedMapOf<String, dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt>()
    private val observedNativeOperations = mutableSetOf<Long>()
    private val roundNativeOperations = mutableSetOf<Long>()
    private var nativeEntryObserved = false
    private var nativeTerminalObserved = false
    private var validation: BenchmarkValidationResult? = null
    private var configured: ProviderEvent.RequestConfigured? = null
    private var first: Long? = null
    private var firstChunkCharacters = 0
    private var lastChunk: Long? = null
    private var longestGap: Long? = null
    private var chunks = 0
    private var inputTokens = 0
    private var roundInputTokens = 0
    private var roundInputUsage = ProviderUsageCounter()
    private var roundOutputUsage = ProviderUsageCounter()
    private var roundReasoningUsage = ProviderUsageCounter()
    private var tokens = 0
    private var estimated = false
    private var roundTokens: Int? = null
    private var roundStart = 0
    private var roundAccounted = true
    private var providerFailure: ProviderEvent.Failed? = null
    private var providerFailureStage: String? = null
    private var finishReason: String? = null
    private var effectiveSettingsConfirmed = false
    private var terminalReceived = false
    private var observedAnswerLength = 0
    private var truncatedAnswerHash: String? = null
    private var retryDurationMs = 0L
    private var reconnectAttempts = 0
    private var requestedOutputTokens: Int? = null
    private var effectiveRequestOutputTokens: Int? = null
    private var requestedReasoningEffort: String? = null
    private var nativeMetrics: dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics? = null
    private val calls = linkedMapOf<String, ProviderEvent.ToolCall>()
    private val successful = mutableSetOf<String>()
    private val code = test.expected.takeIf { test.calculatorExpression != null || test.workload == "permanent_error_decision" } ?: "PKG-${UUID.randomUUID().toString().take(8)}"
    private val scenario = BenchmarkToolScenario(test, code)
    private val expectedErrors = mutableSetOf<String>()
    private fun captureExecution(observed: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? = observeRuntime()) {
        val receipt = observed ?: return
        if (receipt.nativeOperationId !in observedNativeOperations || receipt.observedAtElapsedMs?.let { it < started } == true) return
        val key = listOf(receipt.workerGenerationId, receipt.runtimeRequestId, receipt.physicalAttemptId, receipt.nativeOperationId).joinToString("|")
        val previous = executionReceipts[key]
        executionReceipts[key] = receipt.retainingBenchmarkFacts(previous)
        nativeEntryObserved = nativeEntryObserved || receipt.knownDispatched
        if (receipt.nativeOperationId in roundNativeOperations && receipt.hasNativeTerminal) {
            nativeTerminalObserved = true
            if (finishReason == null && receipt.terminalKind == "output_limit") finishReason = "output_limit"
        }
    }
    private fun roundInputSource() = if (roundInputUsage.value != null) roundInputUsage.source else "unknown"
    private fun roundOutputSource() = if (roundOutputUsage.value != null) {
        roundOutputUsage.source
    } else if (text.length > roundStart) {
        "estimated_characters"
    } else {
        "unknown"
    }
    private fun roundOutputConfidence() = if (roundOutputUsage.value != null) {
        benchmarkUsageConfidence(roundOutputUsage)
    } else if (text.length > roundStart) {
        "estimated"
    } else {
        "unknown"
    }
    private fun sample(outcome: BenchmarkOutcome, error: String? = null): BenchmarkSample {
        captureExecution()
        val measuredAt = now()
        val intervals = phases.snapshot(measuredAt)
        val raw = text.toString()
        val output = if (test.category == "tools") benchmarkOutputView(raw) else BenchmarkOutputView(raw.trim(), listOf(BenchmarkOutputPart(BenchmarkOutputChannel.FINAL_ANSWER, 0, raw.length)), true, false)
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
                finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") && (test.category == "speed" || test.performanceTrial?.acceptsOutputLimit == true) -> "benchmark_cutoff"
                finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") -> "output_limit"
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
            requestDispatched = if (nativeEntryObserved || chunks > 0 || calls.isNotEmpty() || terminalReceived || rounds.isNotEmpty()) true else null,
            terminalReceived = terminalReceived || nativeTerminalObserved,
            canonicalAnswer = text.toString(), answerHash = truncatedAnswerHash ?: benchmarkHash(text.toString()), answerLength = maxOf(observedAnswerLength, text.length), answerTruncated = truncatedAnswerHash != null,
            fixtureHash = benchmarkHash(test.id + "\u0000" + test.prompt + "\u0000" + test.expected),
            validatorVersion = test.validatorVersion,
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
            reasoningTokenConfidence = benchmarkCombinedConfidence(rounds.map { it.reasoningTokenConfidence } + if (roundAccounted) emptyList() else listOf(benchmarkUsageConfidence(roundReasoningUsage))),
            validation = validation,
            runtimeExecutions = executionReceipts.values.toList(),
            outputParts = output.parts,
            finalAnswer = output.finalAnswer,
            repetitionDiagnostic = benchmarkRepetitionDiagnostic(output.finalAnswer),
            fixtureContentHash = test.contentHash,
            repeatIndex = test.repeatIndex,
            requestAdmitted = true.takeIf { nativeEntryObserved || chunks > 0 || calls.isNotEmpty() },
            nativeMetricScope = "last_segment; token totals aggregate the adapter invocation",
            generationDurationMs = benchmarkNativeGenerationDuration(executionReceipts.values.toList()),
            intelligenceSemantics = benchmarkIntelligenceEvidence(test, text.toString(), outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED)),
            toolAssessment = if (test.category == "tools") scenario.assess(calls.values.toList(), successful, expectedErrors, output.finalAnswer, text.toString(), outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED)) else null,
            performanceTrial = test.performanceTrial
        )
    }
    private var roundCompleted = false

    suspend fun run(limitMs: Long): BenchmarkSample {
        try {
            val finished = withTimeoutOrNull(limitMs) { generate() }
            if (finished != true) {
                return sample(
                    BenchmarkOutcome.TIMED_OUT,
                    "Exceeded ${limitMs / 1000}s remaining per-test limit"
                ).copy(failureCategory = "REQUEST_DEADLINE_EXCEEDED", failure = BenchmarkFailure("REQUEST_DEADLINE_EXCEEDED", phases.phase, "Exceeded ${limitMs / 1000}s per-test deadline", owner = "request", retryable = true), runtimeStage = phases.phase, timingEligible = false, throughputExclusion = "REQUEST_DEADLINE_EXCEEDED")
            }
            return validate()
        } catch (cancel: CancellationException) {
            onInterrupted(sample(BenchmarkOutcome.CANCELED, "Request interrupted; observed output is retained.").copy(timingEligible = false, throughputExclusion = "INTERRUPTED"))
            throw cancel
        } catch (error: Exception) {
            captureExecution()
            val qnn = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<dev.chungjungsoo.gptmobile.data.localruntime.QnnRuntimeFailure>()
                .firstOrNull()
            val detail = error.message.orEmpty()
            val reportedQnnStage = Regex("QNN ([A-Z_]+) failed \\[").find(detail)?.groupValues?.getOrNull(1)
            val classified = benchmarkFailure(error, qnn?.stage?.name?.lowercase() ?: reportedQnnStage?.lowercase() ?: providerFailureStage ?: phases.phase)
            val outputLimitFailure = finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") && (providerFailure?.errorCode == null || providerFailure?.errorCode in setOf("OUTPUT_LIMIT", "LOCAL_OUTPUT_LIMIT"))
            val outcome = when {
                classified.code == "NO_VISIBLE_ANSWER" || text.isBlank() && outputLimitFailure -> BenchmarkOutcome.ERROR
                outputLimitFailure && text.isNotBlank() -> BenchmarkOutcome.PARTIAL
                else -> classified.outcome()
            }
            val failure = when {
                text.isBlank() && outputLimitFailure -> classified.copy(code = "NO_VISIBLE_ANSWER")
                outcome == BenchmarkOutcome.PARTIAL && outputLimitFailure -> classified.copy(code = "OUTPUT_LIMIT", owner = "request", scope = "request", failureScope = BenchmarkFailureScope.REQUEST)
                else -> classified
            }
            val raw = text.toString()
            val output = benchmarkOutputView(raw)
            validation = benchmarkValidation(test, raw, if (test.outputPolicy == "TYPED_PROGRESS_V1" && output.protocolComplete) output.finalAnswer else raw.trim(), if (test.category == "tools") code else test.expected, false, if (test.category == "tools") scenario.passed(calls.values.toList(), successful, expectedErrors) else null, listOf(failure.code))
            return sample(outcome, failure.message).copy(
                failureCategory = failure.code,
                failure = failure,
                runtimeStage = failure.stage,
                requestDispatched = when {
                    nativeEntryObserved || chunks > 0 || calls.isNotEmpty() || rounds.isNotEmpty() -> true
                    providerFailure?.providerLimit?.category == "PROVIDER_RATE_LIMITED" -> true
                    providerFailure?.providerLimit?.let { it.knownNotAccepted && it.category in setOf("LOCAL_COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_EXHAUSTED") } == true -> false
                    failure.code in setOf("RESOURCE_ADMISSION", "NATIVE_QUARANTINED", "COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_BLOCKED", "UNSUPPORTED_OUTPUT_CONTRACT", "POLICY_BLOCKED", "STRUCTURED_OUTPUT_MODE_UNSUPPORTED", "STRUCTURED_OUTPUT_COMPATIBILITY_CHANGED") -> false
                    else -> null
                },
                timingEligible = false,
                throughputExclusion = failure.code
            )
        }
    }

    private suspend fun generate(): Boolean {
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
            roundCompleted = false
            terminalReceived = false
            nativeTerminalObserved = false
            roundNativeOperations.clear()
            finishReason = null
            phases.transition("provider_request", rounds.size)
            session.streamRound(tools.map { it.definition }, exchanges.toList()).collect { event ->
                accept(event, roundCalls)
            }
            captureExecution()
            val performanceCutoff = test.performanceTrial?.acceptsOutputLimit == true &&
                calls.isEmpty() &&
                text.isNotBlank() &&
                executionReceipts.values.any { it.nativeOperationId in roundNativeOperations && it.hasNativeTerminal && it.terminalKind == "output_limit" } &&
                segments.sumOf { it.native?.decodeTokens?.coerceAtLeast(0) ?: 0 } >= (test.performanceTrial?.minimumNativeOutputTokens ?: Int.MAX_VALUE) &&
                (providerFailure == null || providerFailure?.errorCode in setOf("OUTPUT_LIMIT", "LOCAL_OUTPUT_LIMIT"))
            val onlyOutputLimitFailure = providerFailure == null || providerFailure?.errorCode in setOf("OUTPUT_LIMIT", "LOCAL_OUTPUT_LIMIT") || providerFailure?.let { it.errorCode == null && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(it.message) } == true
            val throughputCutoff = performanceCutoff || test.category == "speed" && onlyOutputLimitFailure && finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") && text.length >= 100 && calls.isEmpty()
            providerFailure?.let {
                if (it.errorCode == "POLICY_BLOCKED") throw BenchmarkPolicyBlockedException(it.message)
                if (!throughputCutoff) throw BenchmarkProviderFailureException(it.message, it.errorCode, it.providerLimit)
            }
            check(roundCompleted || throughputCutoff) { "Provider ended without a completion event" }
            val outputMeasured = measuredUsageSource(roundOutputUsage.source, roundOutputUsage.phase)
            if (!outputMeasured) estimated = true
            tokens += roundTokens ?: ((text.length - roundStart + 3) / 4)
            inputTokens += roundInputTokens
            rounds.add(BenchmarkRoundMetrics(rounds.size, roundInputTokens, roundTokens ?: ((text.length - roundStart + 3) / 4), !outputMeasured, finishReason, roundCompleted, roundInputSource(), roundOutputSource(), benchmarkUsageConfidence(roundInputUsage), roundOutputConfidence(), roundReasoningUsage.value?.toInt(), if (roundReasoningUsage.value == null) "unknown" else roundReasoningUsage.source, benchmarkUsageConfidence(roundReasoningUsage)))
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
        return true
    }

    private fun accept(event: ProviderEvent, roundCalls: MutableMap<String, ProviderEvent.ToolCall>) {
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
            is ProviderEvent.PhaseChanged -> {
                phases.transition(event.phase.name.lowercase(), rounds.size, event.nativeProgress)
                event.nativeProgress?.let { native ->
                    if (native.operationId > 0 && (native.stage == "NATIVE_ENTRY" || native.stage.startsWith("PREFILL_") || native.stage.startsWith("DECODE_") || native.stage == "NATIVE_RETURN")) {
                        observedNativeOperations += native.operationId
                        roundNativeOperations += native.operationId
                        nativeEntryObserved = true
                        if (native.stage == "NATIVE_RETURN") nativeTerminalObserved = true
                    }
                }
                captureExecution()
            }
            is ProviderEvent.LocalMetrics -> {
                segments.add(event.metrics)
                nativeMetrics = event.metrics.native?.takeIf { it.isValid }
                event.metrics.runtimeExecutionReceipt?.nativeOperationId?.let { operation ->
                    observedNativeOperations += operation
                    roundNativeOperations += operation
                }
                captureExecution(event.metrics.runtimeExecutionReceipt ?: observeRuntime())
            }
            is ProviderEvent.Failed -> {
                providerFailure = event
                providerFailureStage = phases.phase
                terminalReceived = terminalReceived || event.transportComplete
            }
            ProviderEvent.Completed -> {
                roundCompleted = true
                terminalReceived = true
            }
            else -> Unit
        }
    }

    private fun validate(): BenchmarkSample {
        phases.transition("validation")
        val rawAnswer = text.toString()
        val outputView = if (test.category == "tools") benchmarkOutputView(rawAnswer) else BenchmarkOutputView(rawAnswer.trim(), emptyList(), true, false)
        val answer = if (test.outputPolicy == "TYPED_PROGRESS_V1" && outputView.protocolComplete) outputView.finalAnswer else rawAnswer.trim()
        check(!invalidBenchmarkEnvelope(answer)) { "OUTPUT_PROTOCOL_INVALID: Provider protocol metadata is not a benchmark answer." }
        val intelligenceGrade = if (test.id.startsWith("intelligence-batch-")) IntelligenceBenchmark.evaluateBatch(test, answer, test.fixtureSetVersion ?: IntelligenceBenchmark.VERSION) else null
        val timedFixture = test.category == "speed" && listOf("short", "medium", "long").any { test.id.startsWith("speed-$it-") }
        val referenceUnits = ReferenceTextTokenizer.starts(answer).size
        val insufficientTiming = timedFixture && referenceUnits < BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS
        val basePassed = when (test.category) {
            IntelligenceBenchmark.CATEGORY -> if (test.id.startsWith("intelligence-batch-")) intelligenceGrade!!.answers.values.all { it } && (test.validatorVersion != "benchmark-validator-v5" || intelligenceGrade.formatCompliant) else IntelligenceBenchmark.matches(answer, checkNotNull(test.expected))
            "performance" -> calls.isEmpty() && answer.isNotBlank() && benchmarkPerformanceAnswerValid(test, answer) && segments.sumOf { it.native?.decodeTokens?.coerceAtLeast(0) ?: 0 } >= (test.performanceTrial?.minimumNativeOutputTokens ?: 0)
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
        val expected = if (test.category == "tools") code else test.expected
        val details = benchmarkValidation(test, rawAnswer, answer, expected, basePassed, if (test.category == "tools") scenario.passed(calls.values.toList(), successful, expectedErrors) else null)
        val passed = basePassed && (test.validatorVersion !in setOf("benchmark-validator-v4", "benchmark-validator-v5") || details.formatCompliant != false && details.protocolCompliant != false)
        val exclusions = buildList {
            if (test.category == "speed") {
                addAll(details.reasons)
                if (!passed) add("TASK_NOT_PASSED")
                if (insufficientTiming) add("INSUFFICIENT_OUTPUT")
                if (chunks <= 1 || first == null || (lastChunk?.minus(started) ?: 0) <= first!!) add("NO_MEASURABLE_DECODE_INTERVAL")
            }
        }.distinct()
        validation = details.copy(gradePassed = passed, timingExclusions = exclusions)
        val result = sample(
            if (passed) BenchmarkOutcome.PASSED else BenchmarkOutcome.FAILED,
            when {
                insufficientTiming -> "Insufficient timing sample: $referenceUnits reference units produced; ${BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS} required."
                intelligenceGrade != null && !intelligenceGrade.parseable -> "Semantic intelligence ungraded: output is not parseable JSON; raw JSON compliance failed."
                intelligenceGrade != null -> "${intelligenceGrade.answers.values.count { it }}/${intelligenceGrade.answers.size} correct; raw JSON compliance: ${intelligenceGrade.formatCompliant}; parseable: ${intelligenceGrade.parseable}."
                passed -> null
                else -> details.reasons.joinToString("; ").ifBlank { "Response did not meet the fixture's expected result." }
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
        if (finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") && test.category != "speed" && test.performanceTrial?.acceptsOutputLimit != true) {
            return result.copy(outcome = BenchmarkOutcome.PARTIAL, intelligenceSemantics = benchmarkIntelligenceEvidence(test, rawAnswer, complete = false), validation = validation?.copy(gradePassed = false), failureCategory = "OUTPUT_LIMIT", failure = BenchmarkFailure("OUTPUT_LIMIT", "generation", "The final answer reached its output limit.", owner = "request", scope = "request", failureScope = BenchmarkFailureScope.REQUEST), timingEligible = false)
        }
        return if (passed) result else result.copy(failureCategory = if (insufficientTiming) "INSUFFICIENT_TIMING_SAMPLE" else "MODEL_QUALITY")
    }

    private fun message(text: String) = MessageV2(content = text, platformType = null)
}
