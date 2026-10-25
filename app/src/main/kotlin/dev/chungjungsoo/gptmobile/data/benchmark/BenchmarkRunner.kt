package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
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
    val attachments: List<dev.chungjungsoo.gptmobile.data.model.ChatAttachment> = emptyList(),
    val validatorVersion: String = "benchmark-validator-v3",
    val outputPolicy: String = "STRICT_RAW_ANSWER_V1",
    val dependsOn: Set<String> = emptySet(),
    val repeatIndex: Int? = null,
    val cpuExperiment: dev.chungjungsoo.gptmobile.data.localruntime.CpuExperimentTrial? = null,
    val fixtureSetVersion: Int? = null,
    val fixtureRotationSeed: Long? = null,
    val intelligenceQuestions: List<BenchmarkIntelligenceQuestion> = emptyList(),
    val responseSchemaJson: String? = null,
    val performanceTrial: BenchmarkPerformanceTrial? = null
) {
    val requestCategory: String get() = if (performanceTrial != null) {
        "performance_${performanceTrial.dimension.name.lowercase()}"
    } else if (category == IntelligenceBenchmark.CATEGORY && !id.startsWith("intelligence-batch-")) {
        "intelligence_individual"
    } else {
        category
    }
    val contentHash: String get() = benchmarkHash(listOf(category, prompt, expected, workload, validatorVersion, outputPolicy, imageCode).joinToString("\u0000"))
}

fun benchmarkSuite(mode: BenchmarkMode, contextTokens: Int? = null): List<BenchmarkCase> = BenchmarkWorkloadPlanner.adapt(
    BenchmarkSuiteRegistry.cases(mode, 0),
    contextTokens,
    reserveTokens = 1280
)

/** Historical suites keep their original manifest; old measurements never join a new plan. */
internal fun benchmarkSuiteForRun(run: BenchmarkRun): List<BenchmarkCase> {
    run.caseManifest?.let { return it }
    if (run.mode == BenchmarkMode.CPU_EXPERIMENT) return BenchmarkCpuExperiment.cases(run.id)
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 2) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK, paired = true)
    if (run.mode == BenchmarkMode.DELEGATION && run.delegationSuiteVersion == 1) return delegationBenchmarkSuite(run.delegationPlan ?: BenchmarkMode.QUICK)
    if (run.suiteVersion == 6) return BenchmarkWorkloadPlanner.adapt(BenchmarkSuiteRegistry.historical(run.mode), run.fixtureContextTokens)
    if (run.suiteVersion >= 7) return BenchmarkWorkloadPlanner.adapt(benchmarkSuiteForVersion(run.suiteVersion, run.mode, run.fixtureRotationSeed ?: 0), run.fixtureContextTokens, if (run.suiteVersion >= 8) 1280 else 1024)
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
    private val onInterrupted: (BenchmarkSample) -> Unit = {},
    private val observeRuntime: () -> dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? = { null }
) {
    suspend fun run(test: BenchmarkCase, supportsTools: Boolean): BenchmarkSample {
        val first = runOnce(test, supportsTools, timeoutMs)
        if (!allowReconnect) return first
        val remaining = timeoutMs - first.durationMs
        val transient = first.error.orEmpty().lowercase().let { message ->
            listOf("connection abort", "connection reset", "broken pipe", "unexpected end of stream").any { it in message }
        }
        if (first.outcome != BenchmarkOutcome.ERROR || !transient || first.outputCharacters > 0 || first.outputTokens > 0 || first.toolCalls > 0 || first.runtimeExecutions.any { it.knownDispatched } || first.phaseIntervals.any { it.nativeStage == "NATIVE_ENTRY" } || remaining <= 0) return first
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
            retryDurationMs = first.durationMs + retry.retryDurationMs,
            runtimeExecutions = first.runtimeExecutions + retry.runtimeExecutions,
            localSegments = first.localSegments + retry.localSegments,
            rounds = first.rounds + retry.rounds,
            generationDurationMs = benchmarkNativeGenerationDuration(first.runtimeExecutions + retry.runtimeExecutions)
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
        return BenchmarkCaseExecution(openSession, now, onInterrupted, observeRuntime, test).run(limitMs)
    }
}

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
    val saved = mutableMapOf<String, BenchmarkSample>()
    for ((index, test) in suite.withIndex()) {
        val dependency = test.dependsOn.mapNotNull(saved::get).firstOrNull { !it.completed }
        val sample = if (dependency == null) runCase(index, test) else BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.SKIPPED, error = "Required predecessor ${dependency.testId} did not complete.", failureCategory = "SKIPPED_DEPENDENCY", requestDispatched = false, warmup = test.warmup, workload = test.workload)
        saved[test.id] = sample
        onSample(sample)
        val action = sample.recoveryAction()
        val reason = when {
            action == BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES -> null
            benchmarkInfrastructureFailure(sample) -> "Provider or runtime is unavailable; remaining cases were skipped. ${sample.error.orEmpty()}"
            action != BenchmarkRecoveryAction.SKIP_DEPENDENTS -> sample.error ?: "The provider could not complete the benchmark request."
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
