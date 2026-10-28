package dev.chungjungsoo.gptmobile.data.accounting

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsageCounter
import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Entity(tableName = "model_invocations", indices = [Index("turnKey"), Index("startedAt")])
@kotlinx.serialization.Serializable
data class ModelInvocation(
    @PrimaryKey val id: String,
    val parentRunId: String,
    val turnKey: String,
    val provider: String,
    val model: String,
    val kind: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimated: Boolean = true,
    val status: String = "RUNNING",
    val startedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val firstTokenMs: Long? = null,
    val profileUid: String? = null,
    val costMicros: Long? = null,
    val currency: String? = null,
    val priceSource: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'estimated'") val inputTokenSource: String = "estimated",
    @androidx.room.ColumnInfo(defaultValue = "'estimated'") val outputTokenSource: String = "estimated",
    val finishReason: String? = null,
    val actualRuntime: String? = null,
    val executionPolicyJson: String? = null,
    val consumedAssistInvocationIds: String? = null,
    val requestAdmissionReceipts: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val assistInclusionVerified: Boolean = false,
    val observedInputTokens: Long? = null,
    val observedOutputTokens: Long? = null
)

class TokenAllowanceReached : IllegalStateException("The conversation turn reached its total token allowance.")

@Dao
interface InvocationDao {
    @Upsert suspend fun save(invocation: ModelInvocation)

    @Query("SELECT * FROM model_invocations WHERE id = :id")
    suspend fun byId(id: String): ModelInvocation?

    @Query("SELECT i.* FROM model_invocations i LEFT JOIN agent_runs r ON r.run_id = i.parentRunId WHERE r.chat_id = :chatId OR i.turnKey LIKE CAST(:chatId AS TEXT) || ':%' ORDER BY i.startedAt, i.id")
    suspend fun forChat(chatId: Int): List<ModelInvocation>

    @Query("SELECT i.* FROM model_invocations i LEFT JOIN agent_runs r ON r.run_id = i.parentRunId WHERE r.chat_id = :chatId OR i.turnKey LIKE CAST(:chatId AS TEXT) || ':%' ORDER BY i.startedAt, i.id")
    fun observeForChat(chatId: Int): Flow<List<ModelInvocation>>

    @Query("SELECT * FROM model_invocations WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<ModelInvocation>

    @Query("SELECT * FROM model_invocations ORDER BY startedAt DESC LIMIT 100")
    fun recent(): Flow<List<ModelInvocation>>

    @Query("SELECT * FROM model_invocations ORDER BY startedAt DESC LIMIT 10000")
    fun statistics(): Flow<List<ModelInvocation>>

    @Query("SELECT COALESCE(SUM(inputTokens + outputTokens), 0) FROM model_invocations WHERE turnKey = :turnKey")
    suspend fun committedTokens(turnKey: String): Long

    @Query("UPDATE model_invocations SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun recover()

    @Query("DELETE FROM model_invocations")
    suspend fun clear()

    @Query("SELECT COALESCE(SUM(costMicros), 0) FROM model_invocations WHERE turnKey = :turnKey AND currency = :currency")
    suspend fun turnCost(turnKey: String, currency: String): Long

    @Query("SELECT COALESCE(SUM(costMicros), 0) FROM model_invocations WHERE startedAt >= :start AND currency = :currency")
    suspend fun dailyCost(start: Long, currency: String): Long

    @Query("SELECT * FROM model_invocations WHERE turnKey = :turnKey ORDER BY startedAt, id")
    suspend fun forRootBudget(turnKey: String): List<ModelInvocation>

    @Query("SELECT executionPolicyJson FROM model_invocations WHERE turnKey = :turnKey AND executionPolicyJson IS NOT NULL ORDER BY startedAt LIMIT 1")
    suspend fun rootExecutionPolicy(turnKey: String): String?

    @Transaction suspend fun reserve(invocation: ModelInvocation, limit: Int, spend: SpendBudgetSettings = SpendBudgetSettings()) {
        val previous = byId(invocation.id)
        val previousTokens = previous?.takeIf { it.turnKey == invocation.turnKey }?.let { it.inputTokens.toLong() + it.outputTokens } ?: 0
        if (limit != Int.MAX_VALUE && committedTokens(invocation.turnKey) - previousTokens + invocation.inputTokens + invocation.outputTokens > limit) throw TokenAllowanceReached()
        if (spend.enforced && invocation.costMicros == null) throw SpendAllowanceReached("Add a current model price and a finite output cap before using a monetary budget.")
        val cost = invocation.costMicros ?: 0
        val start = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val previousTurnCost = previous?.takeIf { it.turnKey == invocation.turnKey && it.currency == spend.currency }?.costMicros ?: 0
        val previousDailyCost = previous?.takeIf { it.startedAt >= start && it.currency == spend.currency }?.costMicros ?: 0
        if (spend.perTurnMicros > 0 && cost > spend.perTurnMicros - turnCost(invocation.turnKey, spend.currency) + previousTurnCost) throw SpendAllowanceReached("The per-turn spending allowance is reserved or exhausted.")
        if (spend.perDayMicros > 0 && cost > spend.perDayMicros - dailyCost(start, spend.currency) + previousDailyCost) throw SpendAllowanceReached("The UTC daily spending allowance is reserved or exhausted.")
        save(invocation)
    }
}

@Singleton
class InvocationLedger @Inject constructor(
    database: ChatDatabaseV2,
    private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository? = null,
    private val combinedBudgets: dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore? = null
) {
    val dao = database.invocationDao()
    val recent = dao.recent()
    private val liveRequests = MutableStateFlow<Map<String, ModelInvocation>>(emptyMap())
    val active = liveRequests.asStateFlow()

    // Live observations never replace the database reservations used by the token allowance.
    val diagnostics = combine(recent, active, LocalDiagnosticsPolicy.state) { saved, live, enabled ->
        if (enabled) (live.values + saved.filterNot { it.id in live || it.status == "RUNNING" }).distinctBy { it.id }.sortedByDescending { it.startedAt }.take(100) else emptyList()
    }

    /** Only IDs already linked by this chat's retained stage/job receipts are requested. */
    suspend fun linkedStatistics(ids: Set<String>): List<ModelInvocation> {
        if (ids.isEmpty()) return emptyList()
        val saved = ids.toList().chunked(500).flatMap { dao.byIds(it) }
        return (saved.associateBy { it.id } + active.value.filterKeys { it in ids }).values.toList()
    }

    /** Product statistics are available independently of diagnostic logging. */
    fun usageForChat(chatId: Int): Flow<List<ModelInvocation>> = combine(dao.observeForChat(chatId), active) { saved, live ->
        val scoped = live.values.filter { it.turnKey.substringBefore(':') == chatId.toString() || saved.any { row -> row.id == it.id } }
        (saved.associateBy { it.id } + scoped.associateBy { it.id }).values.toList()
    }

    fun diagnosticsForChat(chatId: Int): Flow<List<ModelInvocation>> = combine(dao.observeForChat(chatId), active, LocalDiagnosticsPolicy.state) { saved, live, enabled ->
        if (!enabled) {
            emptyList()
        } else {
            val scoped = live.values.filter { it.turnKey.substringBefore(':') == chatId.toString() || saved.any { row -> row.id == it.id } }
            (saved.associateBy { it.id } + scoped.associateBy { it.id }).values.sortedByDescending { it.startedAt }
        }
    }

    suspend fun recover() {
        // Statistics are user data. Recovery ends abandoned requests without deleting history.
        dao.recover()
    }
    fun wrap(
        session: AgentProviderSession,
        parentRunId: String,
        turnKey: String,
        provider: String,
        model: String,
        kind: String,
        inputEstimate: Int,
        outputLimit: Int,
        totalLimit: Int,
        profileUid: String? = null,
        forceAccounting: Boolean = false,
        preparedAssistEvidence: Set<kotlinx.serialization.json.JsonObject> = emptySet(),
        combined: dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext? = null,
        onInvocation: (String) -> Unit = {}
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally = session.handlesToolsInternally
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            val researchTrace = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.research.ResearchStageTrace]
            val features = settings?.getFeatureSettings()
            val spend = features?.spendBudget ?: SpendBudgetSettings()
            val retainAccounting = researchTrace != null || combined != null || forceAccounting || preparedAssistEvidence.isNotEmpty() || totalLimit != Int.MAX_VALUE || spend.enforced || LocalDiagnosticsPolicy.enabled
            if (!retainAccounting && !LocalDiagnosticsPolicy.enabled) {
                emitAll(session.streamRound(tools, exchanges))
                return@flow
            }
            val replay = exchanges.sumOf { exchange ->
                exchange.calls.sumOf { ContextBudgetService.estimate(it.arguments.toString()) } +
                    exchange.results.sumOf { ContextBudgetService.estimate(it.content.toString()) }
            }
            spend.validate()
            val configured = spend.prices[profileUid]?.takeIf { it.model == model && System.currentTimeMillis() - it.checkedAt in 0..30L * 86400000 }
            val price = if (provider == "LITERT_LM") ModelPrice(model, 0, 0, "On-device inference", System.currentTimeMillis()) else configured
            val estimatedInput = (inputEstimate.toLong() + replay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val reservedCost = price?.takeIf { provider == "LITERT_LM" || outputLimit > 0 }?.cost(estimatedInput, outputLimit)
            val record = ModelInvocation(
                java.util.UUID.randomUUID().toString(),
                parentRunId,
                combined?.rootBudgetId ?: turnKey,
                provider,
                model,
                kind,
                (inputEstimate.toLong() + replay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputLimit,
                profileUid = profileUid,
                durationMs = 0,
                costMicros = reservedCost, currency = spend.currency, priceSource = price?.let { "${it.source} · ${it.checkedAt}" },
                executionPolicyJson = combined?.takeIf { it.persistBudget }?.let { kotlinx.serialization.json.Json.encodeToString(it) }
            )
            try {
                if (retainAccounting) dao.reserve(record, totalLimit, spend)
            } catch (error: SpendAllowanceReached) {
                if (kind == "benchmark") {
                    emit(ProviderEvent.Failed("${error.message} No model request was sent.", errorCode = "POLICY_BLOCKED"))
                } else {
                    emit(ProviderEvent.TextDelta("\n\n${error.message} No model request was sent."))
                    emit(ProviderEvent.Completed)
                }
                return@flow
            } catch (_: TokenAllowanceReached) {
                if (kind == "benchmark") {
                    emit(ProviderEvent.Failed("The benchmark reached its configured token allowance before dispatch. No model request was sent.", errorCode = "POLICY_BLOCKED"))
                } else if (combined != null) {
                    emit(ProviderEvent.Failed("The accepted Combined turn reached its configured total token ceiling before dispatch. Completed evidence is retained."))
                } else {
                    emit(ProviderEvent.TextDelta("\n\nThe response reached its total token allowance. I have paused further model and tool work. Would you like to continue in a new response?"))
                    emit(ProviderEvent.Completed)
                }
                return@flow
            }
            val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(exchanges.flatMap { it.results }.flatMap { it.assistMetadata }.toSet(), parentRunId, record.id, preparedAssistEvidence, researchTrace)
            val combinedAccounting = combined?.let {
                CombinedInvocationAccounting(it, requireNotNull(combinedBudgets ?: dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore.installedOrNull()) { "Combined budget store is unavailable; no dispatch is allowed." }, price, dao, record, totalLimit, spend)
            }
            researchTrace?.invocation(record.id)
            onInvocation(record.id)
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.INVOCATION_STARTED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Request ${record.id} · profileType=$provider · model=$model · runtime=not_resolved_at_dispatch · $kind · input estimate=${record.inputTokens} · parentRunId=$parentRunId · turnKey=$turnKey · requestKind=$kind · profileUid=${profileUid.orEmpty()} · requestedOutputCap=$outputLimit")
            val started = System.nanoTime()
            var first: Long? = null
            var input: Int? = null
            var output: Int? = null
            var inputSource = "estimated"
            var outputSource = "estimated"
            var actualRuntime: String? = null
            var generatedBytes = 0L
            val visibleFingerprint = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.takeIf { it.enabled }?.let { dev.chungjungsoo.gptmobile.data.diagnostics.OutputFingerprintAccumulator() }
            var firstReasoningRecorded = false
            var firstToolRecorded = false
            var visibleBytes = 0L
            var reasoningBytes = 0L
            val inputCounter = ProviderUsageCounter()
            val outputCounter = ProviderUsageCounter()
            fun retainedInput(): Int = if (measuredUsageSource(inputSource)) input ?: record.inputTokens else maxOf(input ?: 0, record.inputTokens)
            fun retainedOutput(): Int = if (measuredUsageSource(outputSource)) output ?: 0 else maxOf(output ?: 0, ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            fun estimatedUsage(): Boolean = input == null || output == null || !measuredUsageSource(inputSource) || !measuredUsageSource(outputSource)
            var providerReasoningTokens: Int? = null
            var finishReason: String? = null
            var settingsConfirmed = false
            var status = "INTERRUPTED"
            var lastPublished = started
            liveRequests.update { it + (record.id to record.copy(outputTokens = 0)) }
            try {
                suspend fun collectRound() {
                    session.streamRound(tools, exchanges).flowOn(dispatch + (combinedAccounting ?: kotlin.coroutines.EmptyCoroutineContext)).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> {
                                if (first == null && event.text.isNotEmpty()) {
                                    first = (System.nanoTime() - started) / 1_000_000
                                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_TEXT, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = first)
                                }
                                val size = event.text.toByteArray().size
                                visibleFingerprint?.append(event.text)
                                visibleBytes += size
                                generatedBytes += size
                            }
                            is ProviderEvent.ToolCall -> if (!firstToolRecorded) {
                                firstToolRecorded = true
                                dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_TOOL, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = (System.nanoTime() - started) / 1_000_000)
                            }
                            is ProviderEvent.ThinkingDelta -> {
                                if (!firstReasoningRecorded && event.text.isNotEmpty()) {
                                    firstReasoningRecorded = true
                                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.FIRST_REASONING, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, elapsedMillis = (System.nanoTime() - started) / 1_000_000)
                                }
                                val size = event.text.toByteArray().size
                                reasoningBytes += size
                                generatedBytes += size
                            }
                            is ProviderEvent.RequestConfigured -> {
                                event.localAdmissionReceipt?.let { receipt ->
                                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_SERIALIZED, role = dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, localAdmissionReceipt = receipt, contextTokens = receipt.contextTokens.toLong(), successful = receipt.accepted)
                                }
                                actualRuntime = event.actualRuntime ?: actualRuntime
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                                    "Model",
                                    "Configured ${record.id} · parentRunId=$parentRunId · requestKind=$kind · profileType=${event.configuredProfileType ?: "not_reported"} · actualRuntime=${event.actualRuntime ?: "not_reported"} · artifactRuntime=${event.artifactRuntime ?: "not_reported"} · actualAccelerator=${event.actualAccelerator ?: "not_reported"} · profileOutputCap=${event.configuredProfileOutputTokens ?: "unset"} · requestedOutputCap=${event.requestedOutputTokens ?: "unset"} · providerOutputCeiling=${event.providerOutputCeiling ?: "unknown"} · serializedOutputCap=${event.serializedOutputTokens ?: "pending"} · effectiveOutputCap=${event.effectiveOutputTokens ?: "unset"}"
                                )
                            }
                            is ProviderEvent.Usage -> {
                                inputCounter.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
                                outputCounter.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
                                input = inputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                output = outputCounter.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                inputSource = inputCounter.source
                                outputSource = outputCounter.source
                                providerReasoningTokens = mergeUsageCount(providerReasoningTokens, event.reasoningTokens, event.cumulative)
                                actualRuntime = event.actualRuntime ?: actualRuntime
                                finishReason = event.finishReason?.take(80) ?: finishReason
                                settingsConfirmed = settingsConfirmed || event.effectiveSettingsConfirmed
                            }
                            ProviderEvent.Completed -> if (status !in setOf("FAILED", "PARTIAL", "WAITING_REMOTE_JOB")) status = "COMPLETED"
                            is ProviderEvent.Failed -> {
                                status = when {
                                    event.errorCode == "QUEUE_JOB_PENDING" -> "WAITING_REMOTE_JOB"
                                    generatedBytes > 0 && (dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(event.message) || event.errorCode == "STREAM_DEGRADED" || event.transportComplete) -> "PARTIAL"
                                    else -> "FAILED"
                                }
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Segment ${record.id} · status=$status · ${event.message}", if (status in setOf("PARTIAL", "WAITING_REMOTE_JOB")) "I" else "E")
                            }
                            else -> Unit
                        }
                        val now = System.nanoTime()
                        if (now - lastPublished >= 250_000_000 || event is ProviderEvent.Completed || event is ProviderEvent.Failed || (first != null && liveRequests.value[record.id]?.firstTokenMs == null)) {
                            lastPublished = now
                            liveRequests.update {
                                it + (
                                    record.id to record.copy(
                                        inputTokens = retainedInput(),
                                        outputTokens = retainedOutput(),
                                        estimated = estimatedUsage(),
                                        inputTokenSource = inputSource,
                                        outputTokenSource = outputSource,
                                        finishReason = finishReason,
                                        actualRuntime = actualRuntime,
                                        durationMs = (now - started) / 1_000_000,
                                        firstTokenMs = first
                                    )
                                    )
                            }
                        }
                        emit(event)
                    }
                }
                collectRound()
            } catch (cancelled: CancellationException) {
                status = "CANCELED"
                throw cancelled
            } catch (error: Exception) {
                status = "FAILED"
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Failed ${record.id}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}", "E")
                throw error
            } finally {
                withContext(NonCancellable) {
                    visibleFingerprint?.snapshot()?.let { fingerprint ->
                        visibleBytes = fingerprint.utf8Bytes
                        generatedBytes = visibleBytes + reasoningBytes
                        dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.OUTPUT_CHECKPOINT, invocationId = record.id, outputBoundary = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.PROVIDER_VISIBLE, outputRepresentation = dev.chungjungsoo.gptmobile.data.diagnostics.OutputTextRepresentation.VISIBLE_UTF8, outputFingerprint = fingerprint)
                    }
                    val unsent = dispatch.provenUnsentAttemptIds()
                    unsent.forEach { combinedAccounting?.notDispatched(it) }
                    val entirelyUnsent = generatedBytes == 0L && dispatch.receipts.isNotEmpty() && dispatch.receipts.all { it.physicalAttemptId in unsent }
                    if (entirelyUnsent && generatedBytes == 0L) {
                        input = 0
                        output = 0
                        inputSource = "not_dispatched"
                        outputSource = "not_dispatched"
                        status = "NOT_DISPATCHED"
                    }
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.INVOCATION_FINISHED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, inputTokens = input?.toLong(), outputTokens = output?.toLong(), visibleBytes = visibleBytes, reasoningBytes = reasoningBytes, elapsedMillis = (System.nanoTime() - started) / 1_000_000, successful = (status == "COMPLETED").takeUnless { status == "WAITING_REMOTE_JOB" }, measured = !estimatedUsage())
                    researchTrace?.usage(
                        dev.chungjungsoo.gptmobile.data.research.ResearchUsageObservation(
                            invocationId = record.id,
                            inputTokens = if (entirelyUnsent) 0 else retainedInput().toLong(),
                            outputTokens = if (entirelyUnsent) 0 else retainedOutput().toLong(),
                            inputSource = inputSource,
                            outputSource = outputSource,
                            visibleOutputEstimate = (visibleBytes + 2) / 3,
                            reasoningTokens = providerReasoningTokens?.toLong(),
                            firstTextMillis = first,
                            durationMillis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                        )
                    )
                    val recordedOutput = if (entirelyUnsent) 0 else retainedOutput()
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Finished ${record.id} · incidentId=${record.id} · status=$status · durationMs=${(System.nanoTime() - started) / 1_000_000} · output=$recordedOutput · estimated=${estimatedUsage()} · inputSource=$inputSource · outputSource=$outputSource · visibleBytes=$visibleBytes · reasoningBytes=$reasoningBytes · usageBreakdownReliable=false · reportedReasoningTokens=${providerReasoningTokens ?: "unknown"} · toolCallTokens=unknown · visibleOutputTokens=unknown · finishReason=${finishReason ?: "unreported"} · settingsConfirmed=$settingsConfirmed · parentRunId=$parentRunId · turnKey=$turnKey · requestKind=$kind", if (status in setOf("COMPLETED", "CANCELED", "WAITING_REMOTE_JOB")) "I" else "W")
                    try {
                        if (retainAccounting) {
                            val observation = record.copy(
                                inputTokens = if (entirelyUnsent) 0 else retainedInput(),
                                outputTokens = if (entirelyUnsent) 0 else retainedOutput(),
                                estimated = !entirelyUnsent && estimatedUsage(),
                                inputTokenSource = inputSource,
                                outputTokenSource = outputSource,
                                finishReason = finishReason,
                                actualRuntime = actualRuntime,
                                status = status,
                                consumedAssistInvocationIds = dispatch.includedProducers.sorted().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                                assistInclusionVerified = dispatch.receipts.isNotEmpty(),
                                requestAdmissionReceipts = dispatch.receipts.toList().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                                observedInputTokens = input?.toLong(),
                                observedOutputTokens = output?.toLong(),
                                costMicros = if (entirelyUnsent) {
                                    0
                                } else if (status == "COMPLETED" && !estimatedUsage()) {
                                    price?.cost(requireNotNull(input), requireNotNull(output))
                                } else {
                                    record.costMicros
                                },
                                durationMs = (System.nanoTime() - started) / 1_000_000,
                                firstTokenMs = first
                            )
                            if (combinedAccounting != null) combinedAccounting.finish(observation, providerReasoningTokens) else dao.save(observation)
                        }
                    } finally {
                        liveRequests.update { it - record.id }
                    }
                }
            }
        }
    }
}
