package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Readiness attempts have their own durable ledger, outside the scored case population. */
internal class BenchmarkReadinessRunner(
    private val metadata: suspend (Boolean, String?) -> BenchmarkReadinessReceipt,
    private val execute: suspend (BenchmarkCase, BenchmarkRequestContext, suspend (BenchmarkSample) -> Unit) -> BenchmarkSample,
    private val persist: suspend (BenchmarkReadinessReceipt) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(plan: List<BenchmarkCase>, policy: BenchmarkExecutionPolicy, local: Boolean, supportsTools: Boolean): BenchmarkReadinessReceipt {
        val schemas = plan.filter { it.responseSchemaJson != null }.groupBy { requireNotNull(it.responseSchemaJson) }
        val requiresTools = supportsTools && plan.any { it.category == "tools" }
        val initial = try {
            metadata(requiresTools, schemas.keys.firstOrNull())
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is BenchmarkStorageFailure) throw failure
            BenchmarkReadinessReceipt(now(), null, listOf(BenchmarkReadinessCheck("configuration", BenchmarkReadinessStatus.FAILED, benchmarkFailure(failure, "readiness").message)), setOf("configuration"), executionPolicy = policy)
        }
        var receipt = initial.copy(finishedAt = null, executionPolicy = policy)
        receipt = receipt.copy(checks = receipt.checks.filterNot { it.id == "json" }, requiredCheckIds = receipt.requiredCheckIds - "json" + schemas.keys.map { "json:${benchmarkHash(it)}" })

        suspend fun save() = withContext(NonCancellable) { persist(receipt) }
        fun check(id: String, status: BenchmarkReadinessStatus, detail: String? = null, sample: BenchmarkSample? = null) {
            receipt = receipt.copy(checks = receipt.checks.filterNot { it.id == id } + BenchmarkReadinessCheck(id, status, detail, sample?.durationMs, sample))
        }
        suspend fun probe(test: BenchmarkCase, mode: BenchmarkStructuredOutputMode): BenchmarkReadinessProbe {
            currentCoroutineContext().ensureActive()
            val schemaDigest = test.responseSchemaJson?.let(::benchmarkHash)
            val pending = BenchmarkReadinessProbe(test.id, mode, null, null, false, schemaDigest, "RUNNING")
            receipt = receipt.copy(probes = receipt.probes + pending)
            save()
            val context = BenchmarkRequestContext(policy, test.responseSchemaJson, PROBE_DEADLINE_MS, readinessProbe = true, structuredOutputMode = mode)
            suspend fun retain(sample: BenchmarkSample) {
                val key = sample.runtimeExecutions.takeIf { it.isNotEmpty() && it.all { execution -> !execution.configuration?.structuredOutputCompatibilityKey.isNullOrBlank() } }
                    ?.map { requireNotNull(it.configuration?.structuredOutputCompatibilityKey) }?.distinct()?.singleOrNull()
                val strict = sample.outcome == BenchmarkOutcome.PASSED &&
                    !sample.answerTruncated &&
                    sample.terminalReceived == true &&
                    (test.category != "json" || runCatching { Json.parseToJsonElement(sample.canonicalAnswer.orEmpty()) == Json.parseToJsonElement(requireNotNull(test.expected)) }.getOrDefault(false)) &&
                    (!local || sample.runtimeCleanup?.safeForSuccessor == true)
                val finished = pending.copy(sample = sample, compatibilityKey = key, passed = strict, state = if (sample.outcome == BenchmarkOutcome.CANCELED) "INTERRUPTED" else "TERMINAL")
                receipt = receipt.copy(probes = receipt.probes.dropLast(1) + finished)
                if (strict && local && sample.runtimeExecutions.any { it.knownDispatched && it.hasNativeTerminal }) {
                    check("initialization", BenchmarkReadinessStatus.PASSED, "A matched native generation completed and cleanup was confirmed.")
                    check("memory", BenchmarkReadinessStatus.PASSED, "Actual measured request was admitted; larger requests still receive fresh admission.")
                    if (sample.runtimeExecutions.any { (it.promptTokens ?: it.configuration?.measuredPromptTokens ?: 0) > 0 }) check("tokenizer", BenchmarkReadinessStatus.PASSED, "The native request tokenized its prompt and generated a response; tokenizer identity may still be unknown.")
                }
                save()
            }
            val sample = execute(test, context, ::retain)
            if (receipt.probes.lastOrNull()?.state == "RUNNING") retain(sample)
            val finished = receipt.probes.last()
            currentCoroutineContext().ensureActive()
            return finished
        }

        save()
        val preparationBlocker = receipt.checks.firstOrNull { it.id in receipt.requiredCheckIds && it.id in setOf("configuration", "model_header") && it.status != BenchmarkReadinessStatus.PASSED }
        if (preparationBlocker != null) {
            receipt = receipt.copy(finishedAt = now())
            save()
            return receipt
        }
        if (schemas.isEmpty()) {
            val test = BenchmarkCase("readiness-text", "Initialization readiness", "task", "Reply with exactly READY and nothing else.", "READY", validatorVersion = "benchmark-validator-v5")
            val result = probe(test, BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1)
            check("initialization", if (result.passed) BenchmarkReadinessStatus.PASSED else BenchmarkReadinessStatus.FAILED, sample = result.sample)
        }
        for ((schema, examples) in schemas) {
            val digest = benchmarkHash(schema)
            val checkId = "json:$digest"
            val modes = listOf(BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1, BenchmarkStructuredOutputMode.PROMPT_JSON_VERIFIED_V1)
            var primaryFailure: BenchmarkReadinessProbe? = null
            var accepted: BenchmarkStructuredOutputMode? = null
            var acceptedKey: String? = null
            for (mode in modes) {
                if (mode == BenchmarkStructuredOutputMode.PROMPT_JSON_VERIFIED_V1) {
                    val failure = primaryFailure?.sample
                    if (!local || failure == null || failure.runtimeCleanup?.safeForSuccessor != true || (failure.failure?.code ?: failure.failureCategory) !in PROMPT_FALLBACK_FAILURES) break
                }
                val completed = mutableListOf<BenchmarkReadinessProbe>()
                repeat(3) { index ->
                    if (completed.lastOrNull()?.passed == false) return@repeat
                    val example = examples[index % examples.size]
                    val expected = example.expected ?: "{\"status\":\"ready\",\"count\":3,\"items\":[\"red\",\"green\",\"blue\"]}"
                    val test = BenchmarkCase("readiness-json-${digest.take(12)}-${mode.name.lowercase()}-$index", "Strict JSON readiness ${index + 1}/3", "json", "Return exactly this JSON value, without Markdown, explanation, or a prefix: $expected", expected, validatorVersion = "benchmark-validator-v5", responseSchemaJson = schema)
                    completed += probe(test, mode)
                }
                val keys = completed.map { it.compatibilityKey }.distinct()
                val identityMatches = !local || keys.size == 1 && keys.single() != null
                if (completed.size == 3 && completed.all { it.passed } && identityMatches) {
                    accepted = mode
                    acceptedKey = keys.singleOrNull()
                    break
                }
                if (mode == BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1) {
                    primaryFailure = completed.firstOrNull { !it.passed } ?: completed.lastOrNull()
                    receipt = receipt.copy(nativePrimaryFailureRetained = true)
                }
            }
            if (accepted == null) {
                check(checkId, BenchmarkReadinessStatus.FAILED, "Three strict JSON probes with one matching loaded configuration were not established.", receipt.probes.lastOrNull()?.sample)
                break
            }
            receipt = receipt.copy(
                schemaModes = receipt.schemaModes + (digest to accepted),
                verifiedStructuredCompatibilityKeys = receipt.verifiedStructuredCompatibilityKeys + if (accepted == BenchmarkStructuredOutputMode.PROMPT_JSON_VERIFIED_V1 && acceptedKey != null) mapOf(digest to acceptedKey) else emptyMap()
            )
            check(checkId, BenchmarkReadinessStatus.PASSED, "Three separate strict JSON probes passed for the exact schema and loaded configuration; mode=${accepted.name}.")
            save()
        }
        if (requiresTools && receipt.requiredCheckIds.filter { it.startsWith("json:") }.all { id -> receipt.checks.singleOrNull { it.id == id }?.status == BenchmarkReadinessStatus.PASSED }) {
            val test = BenchmarkCase("readiness-tools", "Offline tool readiness", "tools", "Call benchmark_lookup exactly once with key=parcel and reply only with its returned code.", validatorVersion = "benchmark-validator-v5", outputPolicy = "TYPED_PROGRESS_V1")
            val result = probe(test, BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1)
            check("tools", if (result.passed) BenchmarkReadinessStatus.PASSED else BenchmarkReadinessStatus.FAILED, sample = result.sample)
        }
        if (!supportsTools && plan.any { it.category == "tools" }) check("tools", BenchmarkReadinessStatus.UNSUPPORTED, "Tool support is unavailable; the main manifest retains unsupported tool cases.")
        receipt = receipt.copy(finishedAt = now(), structuredOutputMode = receipt.schemaModes.values.distinct().singleOrNull() ?: BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1, verifiedStructuredCompatibilityKey = receipt.verifiedStructuredCompatibilityKeys.values.distinct().singleOrNull())
        save()
        return receipt
    }

    companion object {
        const val PROBE_DEADLINE_MS = 90_000L
        val PROMPT_FALLBACK_FAILURES = setOf("CONSTRAINED_DECODING_FAILURE", "STRUCTURED_OUTPUT_MALFORMED", "STRUCTURED_OUTPUT_SCHEMA_MISMATCH", "STRUCTURED_OUTPUT_MODE_UNSUPPORTED")
    }
}
