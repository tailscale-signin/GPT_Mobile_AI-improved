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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Entity(tableName = "model_invocations", indices = [Index("turnKey"), Index("startedAt")])
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
    val priceSource: String? = null
)

class TokenAllowanceReached : IllegalStateException("The conversation turn reached its total token allowance.")

@Dao
interface InvocationDao {
    @Upsert suspend fun save(invocation: ModelInvocation)

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

    @Transaction suspend fun reserve(invocation: ModelInvocation, limit: Int, spend: SpendBudgetSettings = SpendBudgetSettings()) {
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
        profileUid: String? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally = session.handlesToolsInternally
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            val spend = settings?.getFeatureSettings()?.spendBudget ?: SpendBudgetSettings()
            val retainAccounting = totalLimit != Int.MAX_VALUE || spend.enforced || LocalDiagnosticsPolicy.enabled
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
                turnKey,
                provider,
                model,
                kind,
                (inputEstimate.toLong() + replay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputLimit,
                profileUid = profileUid,
                costMicros = reservedCost, currency = spend.currency, priceSource = price?.let { "${it.source} · ${it.checkedAt}" }
            )
            try {
                if (retainAccounting) dao.reserve(record, totalLimit, spend)
            } catch (error: SpendAllowanceReached) {
                emit(ProviderEvent.TextDelta("\n\n${error.message} No model request was sent."))
                emit(ProviderEvent.Completed)
                return@flow
            } catch (_: TokenAllowanceReached) {
                emit(ProviderEvent.TextDelta("\n\nThe response reached its total token allowance. I have paused further model and tool work. Would you like to continue in a new response?"))
                emit(ProviderEvent.Completed)
                return@flow
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Request ${record.id} · $provider / $model · $kind · input estimate=${record.inputTokens}")
            val started = System.nanoTime()
            var first: Long? = null
            var input: Int? = null
            var output: Int? = null
            var generatedBytes = 0L
            var status = "INTERRUPTED"
            var lastPublished = started
            liveRequests.update { it + (record.id to record.copy(outputTokens = 0)) }
            try {
                session.streamRound(tools, exchanges).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (first == null) first = (System.nanoTime() - started) / 1_000_000
                            generatedBytes += event.text.toByteArray().size
                        }
                        is ProviderEvent.ThinkingDelta -> generatedBytes += event.text.toByteArray().size
                        is ProviderEvent.Usage -> {
                            event.inputTokens?.let { input = if (event.cumulative) maxOf(input ?: 0, it) else (input ?: 0) + it }
                            event.outputTokens?.let { output = if (event.cumulative) maxOf(output ?: 0, it) else (output ?: 0) + it }
                        }
                        ProviderEvent.Completed -> if (status != "FAILED") status = "COMPLETED"
                        is ProviderEvent.Failed -> {
                            status = "FAILED"
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Failed ${record.id}: ${event.message}", "E")
                        }
                        else -> Unit
                    }
                    val now = System.nanoTime()
                    if (now - lastPublished >= 500_000_000 || event is ProviderEvent.Usage || (first != null && liveRequests.value[record.id]?.firstTokenMs == null)) {
                        lastPublished = now
                        liveRequests.update {
                            it + (
                                record.id to record.copy(
                                    inputTokens = input ?: record.inputTokens,
                                    outputTokens = output ?: ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    estimated = input == null || output == null,
                                    durationMs = (now - started) / 1_000_000,
                                    firstTokenMs = first
                                )
                                )
                        }
                    }
                    emit(event)
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
                    val recordedOutput = output ?: ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Model", "Finished ${record.id} · status=$status · durationMs=${(System.nanoTime() - started) / 1_000_000} · output=$recordedOutput · estimated=${output == null}", if (status in setOf("COMPLETED", "CANCELED")) "I" else "W")
                    try {
                        if (retainAccounting) {
                            dao.save(
                                record.copy(
                                    inputTokens = input ?: record.inputTokens,
                                    outputTokens = output ?: ((generatedBytes + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    estimated = input == null || output == null,
                                    status = status,
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
