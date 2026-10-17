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

    @Query("SELECT i.* FROM model_invocations i INNER JOIN agent_runs r ON r.run_id = i.parentRunId WHERE r.chat_id = :chatId ORDER BY i.startedAt, i.id")
    suspend fun forChat(chatId: Int): List<ModelInvocation>

    @Query("SELECT i.* FROM model_invocations i INNER JOIN agent_runs r ON r.run_id = i.parentRunId WHERE r.chat_id = :chatId ORDER BY i.startedAt, i.id")
    fun observeForChat(chatId: Int): Flow<List<ModelInvocation>>

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
        invocation.executionPolicyJson?.let { encoded ->
            val requested = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy>(encoded)
            val rows = forRootBudget(invocation.turnKey)
            val effective = rows.mapNotNull { it.executionPolicyJson }.fold(requested) { current, saved ->
                kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy>(saved).restrictWith(current)
            }
            dev.chungjungsoo.gptmobile.data.orchestration.ModelBudgetAdmission.check(rows, invocation, effective)
        }
        if (limit != Int.MAX_VALUE && committedTokens(invocation.turnKey) + invocation.inputTokens + invocation.outputTokens > limit) throw TokenAllowanceReached()
        if (spend.enforced && invocation.costMicros == null) throw SpendAllowanceReached("Add a current model price and a finite output cap before using a monetary budget.")
        val cost = invocation.costMicros ?: 0
        val start = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        if (spend.perTurnMicros > 0 && cost > spend.perTurnMicros - turnCost(invocation.turnKey, spend.currency)) throw SpendAllowanceReached("The per-turn spending allowance is reserved or exhausted.")
        if (spend.perDayMicros > 0 && cost > spend.perDayMicros - dailyCost(start, spend.currency)) throw SpendAllowanceReached("The UTC daily spending allowance is reserved or exhausted.")
        save(invocation)
    }
}

@Singleton
class InvocationLedger @Inject constructor(database: ChatDatabaseV2, private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository? = null) {
    val dao = database.invocationDao()
    val recent = dao.recent()
    private val liveRequests = MutableStateFlow<Map<String, ModelInvocation>>(emptyMap())
    val active = liveRequests.asStateFlow()

    // Live observations never replace the database reservations used by the token allowance.
    val diagnostics = combine(recent, active, LocalDiagnosticsPolicy.state) { saved, live, enabled ->
        if (enabled) (live.values + saved.filterNot { it.id in live || it.status == "RUNNING" }).distinctBy { it.id }.sortedByDescending { it.startedAt }.take(100) else emptyList()
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
        onInvocation: (String) -> Unit = {}
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally = session.handlesToolsInternally
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            val features = settings?.getFeatureSettings()
            val spend = features?.spendBudget ?: SpendBudgetSettings()
            val selectedPolicy = features?.executionPolicy ?: dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy()
            val retainedPolicy = if (settings != null) dao.rootExecutionPolicy(turnKey)?.let { kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy>(it) } else null
            val executionPolicy = (retainedPolicy?.restrictWith(selectedPolicy) ?: selectedPolicy).validate()
            val retainAccounting = forceAccounting || preparedAssistEvidence.isNotEmpty() || totalLimit != Int.MAX_VALUE || spend.enforced || executionPolicy.unifiedRunAccounting || executionPolicy.sharedRunBudget || LocalDiagnosticsPolicy.enabled
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
            val activeAllowance = executionPolicy.modelActiveMillis?.takeIf { executionPolicy.sharedRunBudget }?.let { limit ->
                val used = dao.forRootBudget(turnKey).sumOf { it.durationMs }
                ((limit - used).coerceAtLeast(1) / executionPolicy.concurrentModels).coerceAtLeast(1)
            }
            val record = ModelInvocation(
                java.util.UUID.randomUUID().toString(),
                parentRunId,
                turnKey,
                provider,
                model,
                kind,
                (inputEstimate.toLong() + replay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputLimit,
                profileUid = profileUid,
                durationMs = activeAllowance ?: 0,
                costMicros = reservedCost, currency = spend.currency, priceSource = price?.let { "${it.source} · ${it.checkedAt}" },
                executionPolicyJson = if (executionPolicy.sharedRunBudget) kotlinx.serialization.json.Json.encodeToString(executionPolicy) else null
            )
            try {
                if (retainAccounting) dao.reserve(record, totalLimit, spend)
            } catch (error: dev.chungjungsoo.gptmobile.data.orchestration.ExecutionAllowanceReached) {
                emit(ProviderEvent.Failed("${error.message} No model request was sent; completed evidence remains available."))
                return@flow
            } catch (error: SpendAllowanceReached) {
                emit(ProviderEvent.TextDelta("\n\n${error.message} No model request was sent."))
                emit(ProviderEvent.Completed)
                return@flow
            } catch (_: TokenAllowanceReached) {
                emit(ProviderEvent.TextDelta("\n\nThe response reached its total token allowance. I have paused further model and tool work. Would you like to continue in a new response?"))
                emit(ProviderEvent.Completed)
                return@flow
            }
            val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(exchanges.flatMap { it.results }.flatMap { it.assistMetadata }.toSet(), parentRunId, record.id, preparedAssistEvidence)
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
            var providerReasoningTokens: Int? = null
            var finishReason: String? = null
            var settingsConfirmed = false
            var status = "INTERRUPTED"
            var lastPublished = started
            liveRequests.update { it + (record.id to record.copy(outputTokens = 0)) }
            try {
                suspend fun collectRound() {
                    session.streamRound(tools, exchanges).flowOn(dispatch).collect { event ->
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
                                actualRuntime = event.actualRuntime ?: actualRuntime
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                                    "Model",
                                    "Configured ${record.id} · parentRunId=$parentRunId · requestKind=$kind · profileType=${event.configuredProfileType ?: "not_reported"} · actualRuntime=${event.actualRuntime ?: "not_reported"} · artifactRuntime=${event.artifactRuntime ?: "not_reported"} · actualAccelerator=${event.actualAccelerator ?: "not_reported"} · profileOutputCap=${event.configuredProfileOutputTokens ?: "unset"} · requestedOutputCap=${event.requestedOutputTokens ?: "unset"} · effectiveOutputCap=${event.effectiveOutputTokens ?: "unset"}"
                                )
                            }
                            is ProviderEvent.Usage -> {
                                input = if (event.inputSource == "runtime_reported" && inputSource != "runtime_reported") event.inputTokens ?: input else mergeUsageCount(input, event.inputTokens, event.cumulative)
                                output = if (event.outputSource == "runtime_reported" && outputSource != "runtime_reported") event.outputTokens ?: output else mergeUsageCount(output, event.outputTokens, event.cumulative)
                                if (event.inputTokens != null && event.inputSource != "unavailable") inputSource = event.inputSource
                                if (event.outputTokens != null && event.outputSource != "unavailable") outputSource = event.outputSource
                                providerReasoningTokens = mergeUsageCount(providerReasoningTokens, event.reasoningTokens, event.cumulative)
                                actualRuntime = event.actualRuntime ?: actualRuntime
                                finishReason = event.finishReason?.take(80) ?: finishReason
                                settingsConfirmed = settingsConfirmed || event.effectiveSettingsConfirmed
                            }
                            ProviderEvent.Completed -> if (status !in setOf("FAILED", "PARTIAL")) status = "COMPLETED"
                            is ProviderEvent.Failed -> {
                                status = if (generatedBytes > 0 && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(event.message)) "PARTIAL" else "FAILED"
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Segment ${record.id} · status=$status · ${event.message}", if (status == "PARTIAL") "I" else "E")
                            }
                            else -> Unit
                        }
                        val now = System.nanoTime()
                        if (now - lastPublished >= 250_000_000 || event is ProviderEvent.Completed || event is ProviderEvent.Failed || (first != null && liveRequests.value[record.id]?.firstTokenMs == null)) {
                            lastPublished = now
                            liveRequests.update {
                                it + (
                                    record.id to record.copy(
                                        inputTokens = input ?: record.inputTokens,
                                        outputTokens = output ?: if (status != "COMPLETED" && executionPolicy.sharedRunBudget) maxOf(record.outputTokens, ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        estimated = input == null || output == null || inputSource == "estimated" || outputSource == "estimated",
                                        inputTokenSource = inputSource,
                                        outputTokenSource = if (output == null && status != "COMPLETED" && executionPolicy.sharedRunBudget) "reserved_unknown" else outputSource,
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
                val completed = if (activeAllowance == null) {
                    collectRound()
                    true
                } else {
                    kotlinx.coroutines.withTimeoutOrNull(activeAllowance) {
                        collectRound()
                        true
                    } ?: false
                }
                if (!completed) {
                    status = if (generatedBytes > 0) "PARTIAL" else "FAILED"
                    finishReason = "operation_deadline"
                    emit(ProviderEvent.Failed("Active model time allowance reached; completed evidence and partial output are retained."))
                }
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
                    dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(parentRunId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.INVOCATION_FINISHED, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.from(kind), invocationId = record.id, inputTokens = input?.toLong(), outputTokens = output?.toLong(), visibleBytes = visibleBytes, reasoningBytes = reasoningBytes, elapsedMillis = (System.nanoTime() - started) / 1_000_000, successful = status == "COMPLETED", measured = input != null && output != null)
                    val recordedOutput = output ?: ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Finished ${record.id} · status=$status · durationMs=${(System.nanoTime() - started) / 1_000_000} · output=$recordedOutput · estimated=${input == null || output == null} · inputSource=$inputSource · outputSource=$outputSource · visibleBytes=$visibleBytes · reasoningBytes=$reasoningBytes · reportedReasoningTokens=${providerReasoningTokens ?: "unknown"} · toolCallTokens=unknown · visibleOutputTokens=unknown · finishReason=${finishReason ?: "unreported"} · settingsConfirmed=$settingsConfirmed · parentRunId=$parentRunId · turnKey=$turnKey · requestKind=$kind", if (status in setOf("COMPLETED", "CANCELED")) "I" else "W")
                    try {
                        if (retainAccounting) {
                            dao.save(
                                record.copy(
                                    inputTokens = input ?: record.inputTokens,
                                    outputTokens = output ?: if (status != "COMPLETED" && executionPolicy.sharedRunBudget) maxOf(record.outputTokens, ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    estimated = input == null || output == null || inputSource == "estimated" || outputSource == "estimated",
                                    inputTokenSource = inputSource,
                                    outputTokenSource = if (output == null && status != "COMPLETED" && executionPolicy.sharedRunBudget) "reserved_unknown" else outputSource,
                                    finishReason = finishReason,
                                    actualRuntime = actualRuntime,
                                    status = status,
                                    consumedAssistInvocationIds = dispatch.includedProducers.sorted().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                                    assistInclusionVerified = dispatch.receipts.isNotEmpty(),
                                    requestAdmissionReceipts = dispatch.receipts.toList().takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.Json.encodeToString(it) },
                                    observedInputTokens = input?.toLong(),
                                    observedOutputTokens = output?.toLong(),
                                    costMicros = if (status == "COMPLETED" && input != null && output != null) price?.cost(requireNotNull(input), requireNotNull(output)) else record.costMicros,
                                    durationMs = (System.nanoTime() - started) / 1_000_000,
                                    firstTokenMs = first
                                )
                            )
                        }
                    } finally {
                        liveRequests.update { it - record.id }
                    }
                }
            }
        }
    }
}
