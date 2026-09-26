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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    val firstTokenMs: Long? = null
)

@Dao
interface InvocationDao {
    @Upsert suspend fun save(invocation: ModelInvocation)

    @Query("SELECT * FROM model_invocations ORDER BY startedAt DESC LIMIT 100")
    fun recent(): Flow<List<ModelInvocation>>

    @Query("SELECT COALESCE(SUM(inputTokens + outputTokens), 0) FROM model_invocations WHERE turnKey = :turnKey")
    suspend fun committedTokens(turnKey: String): Long

    @Query("UPDATE model_invocations SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun recover()

    @Transaction suspend fun reserve(invocation: ModelInvocation, limit: Int) {
        check(committedTokens(invocation.turnKey) + invocation.inputTokens + invocation.outputTokens <= limit) {
            "The conversation turn reached its total token budget, including other models, delegates and synthesis. Increase the limit in Tool connections to continue."
        }
        save(invocation)
    }
}

@Singleton
class InvocationLedger @Inject constructor(database: ChatDatabaseV2) {
    val dao = database.invocationDao()
    val recent = dao.recent()
    fun wrap(
        session: AgentProviderSession,
        parentRunId: String,
        turnKey: String,
        provider: String,
        model: String,
        kind: String,
        inputEstimate: Int,
        outputLimit: Int,
        totalLimit: Int
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally = session.handlesToolsInternally
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            val replay = exchanges.sumOf { exchange ->
                exchange.calls.sumOf { ContextBudgetService.estimate(it.arguments.toString()) } +
                    exchange.results.sumOf { ContextBudgetService.estimate(it.content.toString()) }
            }
            val record = ModelInvocation(
                java.util.UUID.randomUUID().toString(),
                parentRunId,
                turnKey,
                provider,
                model,
                kind,
                (inputEstimate.toLong() + replay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputLimit
            )
            dao.reserve(record, totalLimit)
            val started = System.nanoTime()
            var first: Long? = null
            var input: Int? = null
            var output: Int? = null
            var characters = 0L
            var completed = false
            try {
                session.streamRound(tools, exchanges).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (first == null) first = (System.nanoTime() - started) / 1_000_000
                            characters += event.text.length
                        }
                        is ProviderEvent.ThinkingDelta -> characters += event.text.length
                        is ProviderEvent.Usage -> {
                            event.inputTokens?.let { input = if (event.cumulative) maxOf(input ?: 0, it) else (input ?: 0) + it }
                            event.outputTokens?.let { output = if (event.cumulative) maxOf(output ?: 0, it) else (output ?: 0) + it }
                        }
                        ProviderEvent.Completed -> completed = true
                        else -> Unit
                    }
                    emit(event)
                }
            } finally {
                withContext(NonCancellable) {
                    dao.save(
                        record.copy(
                            inputTokens = input ?: record.inputTokens,
                            outputTokens = output ?: ((characters + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            estimated = input == null || output == null,
                            status = if (completed) "COMPLETED" else "STOPPED",
                            durationMs = (System.nanoTime() - started) / 1_000_000,
                            firstTokenMs = first
                        )
                    )
                }
            }
        }
    }
}
