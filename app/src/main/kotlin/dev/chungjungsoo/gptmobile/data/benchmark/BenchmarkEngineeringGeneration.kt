package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsageCounter
import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Only these runner-owned observations can become a local model-contract failure. */
private class BenchmarkGenerationContractViolation(val code: String, val observationDigest: String) : IllegalStateException(code)

/**
 * A ledger invocation can wrap several physical requests. Its last cumulative usage frame
 * cannot certify all of them. Only explicit owners, or a single observed request under one
 * invocation, establish that relationship; callback timing is never used to guess a frame's owner.
 */
internal class BenchmarkEngineeringGenerationUsage {
    private class Owner {
        val input = ProviderUsageCounter()
        val output = ProviderUsageCounter()
        val reasoning = ProviderUsageCounter()
        val cached = ProviderUsageCounter()
        var settingsConfirmed = false
    }

    data class Tokens(val value: Int?, val source: String, val confidence: String) {
        val complete: Boolean get() = confidence == "final_reported"
    }

    data class Snapshot(
        val input: Tokens,
        val output: Tokens,
        val reasoning: Tokens,
        val cached: Tokens,
        val settingsConfirmed: Boolean,
        val physicalRequestIds: List<String>,
        val invocationIds: List<String>,
        val retryAttempts: Int,
        val retryDurationMs: Long,
        val evidence: Map<String, String>
    )

    private val owners = linkedMapOf<String?, Owner>()
    private val invocations = linkedSetOf<String>()
    private val physicalOwners = linkedMapOf<String, String?>()
    private var currentInvocation: String? = null
    private var reportedRetries = 0
    private var retryDurationMs = 0L

    @Synchronized fun invocation(id: String) {
        invocations += id
        currentInvocation = id
    }

    @Synchronized fun physicalRequest(id: String) {
        // This captures the ledger relationship at dispatch, not the later arrival order of
        // buffered usage frames. Repeated observer delivery does not create another request.
        if (id !in physicalOwners) physicalOwners[id] = currentInvocation
    }

    @Synchronized fun accept(event: ProviderEvent.Usage) {
        val owner = owners.getOrPut(event.invocationId) { Owner() }
        owner.input.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
        owner.output.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
        owner.reasoning.accept(event.reasoningTokens, event.cumulative, event.outputSource, event.phase)
        owner.cached.accept(event.cachedPromptTokens, event.cumulative, event.inputSource, event.phase)
        // A confirmation can precede a token frame, but it belongs only to this owner.
        owner.settingsConfirmed = owner.settingsConfirmed || event.effectiveSettingsConfirmed
        reportedRetries = maxOf(reportedRetries, event.retryAttempts ?: 0)
        retryDurationMs = maxOf(retryDurationMs, event.retryDurationMs ?: 0)
    }

    @Synchronized fun snapshot(): Snapshot {
        val groups = linkedMapOf<String?, MutableList<Owner>>()
        val assigned = mutableSetOf<String?>()
        if (physicalOwners.isNotEmpty()) {
            val requestsPerInvocation = physicalOwners.values.filterNotNull().groupingBy { it }.eachCount()
            physicalOwners.keys.forEach { groups[it] = mutableListOf() }
            owners.forEach { (id, owner) ->
                val matches = physicalOwners.filter { (request, invocation) ->
                    id == request ||
                        id != null &&
                        id == invocation &&
                        requestsPerInvocation[invocation] == 1 ||
                        id == null &&
                        physicalOwners.size == 1
                }
                if (matches.size == 1) {
                    groups.getValue(matches.keys.single()) += owner
                    assigned += id
                }
            }
        } else {
            // Adapters without a physical callback retain their explicit invocation scope.
            // An unlabelled observation can describe one owner, never several retries.
            val ids = (invocations + owners.keys.filterNotNull()).toSet()
            if (ids.isEmpty() && owners.containsKey(null)) {
                groups[null] = mutableListOf(owners.getValue(null))
                assigned += null
            } else {
                ids.forEach { id ->
                    groups[id] = mutableListOf<Owner>().apply {
                        owners[id]?.let {
                            add(it)
                            assigned += id
                        }
                        if (ids.size == 1) {
                            owners[null]?.let {
                                add(it)
                                assigned += null
                            }
                        }
                    }
                }
            }
        }
        val unattributed = owners.filterKeys { it !in assigned }.values.toList()
        val ownershipComplete = physicalOwners.isNotEmpty() &&
            groups.isNotEmpty() &&
            groups.values.all { it.size == 1 } &&
            unattributed.isEmpty() &&
            reportedRetries < groups.size

        fun tokens(select: (Owner) -> ProviderUsageCounter): Tokens {
            // Aliases or an aggregate wrapper may overlap direct observations. Preserve the
            // strongest observed subtotal without adding possibly duplicated receipts.
            val attributed = groups.values.mapNotNull { records -> records.mapNotNull { select(it).value }.maxOrNull() }
            val unmatched = unattributed.mapNotNull { select(it).value }.maxOrNull()
            val value = (attributed.takeIf { it.isNotEmpty() }?.sum()?.let { maxOf(it, unmatched ?: 0) } ?: unmatched)
                ?: return Tokens(null, "unavailable", "unknown")
            val counters = owners.values.map(select).filter { it.value != null }
            val complete = ownershipComplete &&
                value <= Int.MAX_VALUE &&
                groups.values.all { records ->
                    val counter = select(records.single())
                    counter.value != null && measuredUsageSource(counter.source, counter.phase)
                }
            val source = when {
                complete && counters.map { it.source }.distinct().size == 1 -> counters.first().source
                complete -> "mixed_reported"
                counters.all { it.source.contains("estimated") } -> "estimated"
                else -> "mixed_reported_partial"
            }
            return Tokens(
                value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                source,
                if (complete) {
                    "final_reported"
                } else if (source == "estimated") {
                    "estimated"
                } else {
                    "partial_observation"
                }
            )
        }
        val input = tokens { it.input }
        val output = tokens { it.output }
        val reasoning = tokens { it.reasoning }
        val cached = tokens { it.cached }
        return Snapshot(
            input, output, reasoning, cached,
            ownershipComplete && groups.values.all { it.single().settingsConfirmed },
            physicalOwners.keys.toList(), invocations.toList(),
            maxOf(reportedRetries, (groups.size - 1).coerceAtLeast(0)), retryDurationMs,
            buildMap {
                put("usageAccountingVersion", "physical-owner-v1")
                put("usageOwnershipComplete", ownershipComplete.toString())
                put("observedPhysicalRequests", physicalOwners.size.toString())
                put("observedUsageOwners", owners.size.toString())
                put("unattributedUsageOwners", unattributed.size.toString())
                put("inputUsageComplete", input.complete.toString())
                put("outputUsageComplete", output.complete.toString())
                put("cachedUsageComplete", cached.complete.toString())
                input.value?.let { put("recordedInputTokenSubtotal", it.toString()) }
                output.value?.let { put("recordedOutputTokenSubtotal", it.toString()) }
                cached.value?.let { put("recordedCachedTokenSubtotal", it.toString()) }
            }
        )
    }
}

/** Collect the real selected provider's proposal without executing any model-selected tool. */
internal suspend fun benchmarkEngineeringGeneration(
    chats: ChatRepository,
    profile: PlatformV2,
    test: BenchmarkCase,
    prompt: String,
    schema: String?,
    plan: BenchmarkEngineeringPlan,
    logicalRequestId: String,
    deadlineMs: Long,
    onInterrupted: (BenchmarkSample) -> Unit
): BenchmarkSample {
    val start = System.nanoTime() / 1_000_000
    val text = StringBuilder()
    val usage = BenchmarkEngineeringGenerationUsage()
    val native = mutableListOf<dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics>()
    var first: Long? = null
    var last: Long? = null
    var chunks = 0
    var terminal = false
    var failure: ProviderEvent.Failed? = null
    var configured: ProviderEvent.RequestConfigured? = null
    var finish: String? = null
    var contractViolation: BenchmarkGenerationContractViolation? = null
    fun snapshot(outcome: BenchmarkOutcome, code: String? = null): BenchmarkSample {
        val observed = usage.snapshot()
        val input = observed.input
        val output = observed.output
        val reasoning = observed.reasoning
        return BenchmarkSample(
            test.id, test.label, test.category, outcome,
            durationMs = (System.nanoTime() / 1_000_000 - start).coerceAtLeast(0),
            firstTextMs = first,
            inputTokens = input.value ?: 0,
            outputTokens = output.value ?: (text.length + 3) / 4,
            estimatedTokens = !output.complete,
            outputCharacters = text.length,
            chunks = chunks,
            lastTextMs = last,
            canonicalAnswer = text.toString(),
            finalAnswer = text.toString(),
            answerHash = benchmarkHash(text.toString()),
            answerLength = text.length,
            preview = text.take(1000).toString(),
            logicalRequestId = logicalRequestId,
            physicalRequestIds = observed.physicalRequestIds,
            invocationIds = observed.invocationIds,
            requestDispatched = observed.physicalRequestIds.isNotEmpty() || terminal || text.isNotEmpty() || contractViolation != null,
            terminalReceived = terminal,
            requestedOutputTokens = plan.budget.outputTokensPerGeneration,
            effectiveRequestOutputTokens = configured?.effectiveOutputTokens,
            requestedReasoningEffort = configured?.reasoningEffort,
            effectiveSettingsConfirmed = observed.settingsConfirmed,
            inputTokenSource = input.source,
            outputTokenSource = if (output.value == null) "estimated_characters" else output.source,
            inputTokenConfidence = input.confidence,
            outputTokenConfidence = if (output.value == null) "estimated" else output.confidence,
            reasoningTokens = reasoning.value,
            reasoningTokenSource = reasoning.source,
            reasoningTokenConfidence = reasoning.confidence,
            reportedCachedPromptTokens = observed.cached.value.takeIf { observed.cached.complete },
            rounds = listOf(BenchmarkRoundMetrics(0, input.value ?: 0, output.value ?: (text.length + 3) / 4, !output.complete, finish, terminal, input.source, output.source, input.confidence, if (output.value == null) "estimated" else output.confidence, reasoning.value, reasoning.source, reasoning.confidence)),
            localSegments = native.toList(),
            runtimeExecutions = native.mapNotNull { it.runtimeExecutionReceipt },
            timingEligible = false,
            throughputExclusion = "NOT_A_TIMING_WORKLOAD",
            deliveryMode = if (chunks > 1) "streaming" else "buffered",
            workload = test.workload,
            finishReason = finish,
            retryDurationMs = observed.retryDurationMs,
            reconnectAttempts = observed.retryAttempts,
            failureCategory = code,
            error = code,
            evidenceScope = plan.lane.evidenceClass,
            completionKind = if (contractViolation != null) {
                "local_generation_contract_failed"
            } else if (terminal) {
                "proposal_received"
            } else {
                "incomplete_generation"
            },
            adapterRequestSettings = buildMap {
                putAll(observed.evidence)
                contractViolation?.let {
                    put("generationContractReason", it.code)
                    put("generationContractEvidenceDigest", it.observationDigest)
                }
                configured?.requestedOutputTokens?.let { put("requestedOutputTokens", it.toString()) }
                configured?.effectiveOutputTokens?.let { put("effectiveOutputTokens", it.toString()) }
                configured?.reasoningEffort?.let { put("reasoningEffort", it) }
                configured?.finalResponseFormat?.let { put("finalResponseFormat", it) }
            }
        )
    }
    try {
        withTimeout(deadlineMs) {
            withContext(BenchmarkRequestContext(plan.executionPolicy, schema, deadlineMs)) {
                val session = chats.openBenchmarkSession(
                    profile,
                    listOf(ConversationTurn(MessageV2(content = prompt, platformType = null), null, true)),
                    emptyList(),
                    logicalRequestId,
                    "engineering_tools_output_${plan.budget.outputTokensPerGeneration}",
                    onInvocation = usage::invocation,
                    onPhysicalRequest = usage::physicalRequest
                )
                session.streamRound(emptyList(), emptyList()).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> if (event.text.isNotEmpty()) {
                            val elapsed = System.nanoTime() / 1_000_000 - start
                            if (first == null) first = elapsed
                            last = elapsed
                            chunks++
                            if (text.length.toLong() + event.text.length > 65_536) {
                                throw BenchmarkGenerationContractViolation("PATCH_OUTPUT_TOO_LARGE", benchmarkHash("utf16-bound:65536:${text.length}:${event.text.length}:${benchmarkHash(event.text)}"))
                            }
                            text.append(event.text)
                        }
                        is ProviderEvent.Usage -> {
                            usage.accept(event)
                            finish = event.finishReason ?: finish
                        }
                        is ProviderEvent.RequestConfigured -> configured = event
                        is ProviderEvent.LocalMetrics -> native += event.metrics
                        is ProviderEvent.Failed -> failure = event
                        is ProviderEvent.ToolCall -> throw BenchmarkGenerationContractViolation("ENGINEERING_UNEXPECTED_TOOL_CALL", benchmarkHash("no-tools:${benchmarkHash(event.name)}"))
                        ProviderEvent.Completed -> terminal = true
                        else -> Unit
                    }
                }
            }
        }
        val proposalBytes = text.toString().encodeToByteArray().size
        if (proposalBytes > 65_536) {
            throw BenchmarkGenerationContractViolation("PATCH_OUTPUT_TOO_LARGE", benchmarkHash("utf8-bound:65536:$proposalBytes:${benchmarkHash(text.toString())}"))
        }
        val code = when {
            failure != null -> failure?.errorCode ?: "PROVIDER_GENERATION_FAILED"
            !terminal -> "PROVIDER_TERMINAL_MISSING"
            finish?.lowercase() in setOf("length", "max_tokens", "output_limit") -> "PATCH_OUTPUT_TRUNCATED"
            text.isBlank() -> "NO_VISIBLE_ANSWER"
            else -> null
        }
        return snapshot(if (code == null) BenchmarkOutcome.PARTIAL else BenchmarkOutcome.ERROR, code)
    } catch (cancel: CancellationException) {
        onInterrupted(snapshot(BenchmarkOutcome.CANCELED, "ENGINEERING_GENERATION_INTERRUPTED"))
        throw cancel
    } catch (error: Exception) {
        if (error is BenchmarkStorageFailure) throw error
        if (error is BenchmarkGenerationContractViolation) {
            contractViolation = error
            return snapshot(BenchmarkOutcome.FAILED, error.code)
        }
        val safe = error.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: benchmarkFailure(error, "generation").code
        return snapshot(BenchmarkOutcome.ERROR, safe)
    }
}
