package dev.chungjungsoo.gptmobile.data.openrouter.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Identity contains no API key. Each rotated key and each billable generation has its own record. */
@Serializable
data class OpenRouterReceiptContext(
    val connectionUid: String,
    val credentialRevision: String,
    val endpoint: String,
    val profileUid: String,
    val model: String,
    val role: String,
    val attemptId: String? = null,
    val policy: String = "{}",
    val accountBindingRevision: String? = null
)

@Serializable
data class OpenRouterReceipt(
    val localId: String = UUID.randomUUID().toString(),
    val context: OpenRouterReceiptContext,
    val generationId: String? = null,
    val resolvedModel: String? = null,
    val provider: String? = null,
    val costUsd: String? = null,
    val providerCostUsd: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheStatus: String? = null,
    val status: String = "pending",
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val remoteEvidence: Boolean = false
) {
    val identity: String get() = "${context.connectionUid}|${context.credentialRevision}|$localId"
    fun merge(newer: OpenRouterReceipt): OpenRouterReceipt = newer.copy(
        localId = localId, createdAt = minOf(createdAt, newer.createdAt), costUsd = newer.costUsd ?: costUsd,
        providerCostUsd = newer.providerCostUsd ?: providerCostUsd, inputTokens = newer.inputTokens ?: inputTokens,
        outputTokens = newer.outputTokens ?: outputTokens, cachedTokens = newer.cachedTokens ?: cachedTokens,
        provider = newer.provider ?: provider, resolvedModel = newer.resolvedModel ?: resolvedModel,
        cacheStatus = newer.cacheStatus ?: cacheStatus,
        generationId = newer.generationId ?: generationId,
        remoteEvidence = remoteEvidence || newer.remoteEvidence,
        status = if (newer.status == "pending") status else newer.status
    )
}

@Singleton
class OpenRouterReceiptStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val wallet: OpenRouterWalletRefreshCoordinator? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("openrouter_receipts_v1")
    fun observe(connectionUid: String): Flow<List<OpenRouterReceipt>> = dataStore.data.map { prefs ->
        decode(prefs[key]).filter { it.context.connectionUid == connectionUid }.sortedByDescending { it.createdAt }
    }
    suspend fun record(receipt: OpenRouterReceipt): OpenRouterReceipt {
        var saved = receipt
        dataStore.edit { prefs ->
            val records = decode(prefs[key])
            val previous = records.firstOrNull {
                it.context.connectionUid == receipt.context.connectionUid &&
                    it.context.credentialRevision == receipt.context.credentialRevision &&
                    (it.localId == receipt.localId || receipt.generationId != null && it.generationId == receipt.generationId)
            }
            saved = previous?.merge(receipt) ?: receipt
            prefs[key] = json.encodeToString((records.filterNot { it.localId == previous?.localId } + saved).sortedByDescending { it.createdAt }.take(500))
        }
        return saved
    }
    suspend fun capture(context: OpenRouterReceiptContext): OpenRouterReceiptContext = wallet?.capture(context) ?: context
    suspend fun finalized(receipt: OpenRouterReceipt) {
        wallet?.finalized(receipt)
    }
    suspend fun clear(connectionUid: String) {
        dataStore.edit { prefs -> prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.context.connectionUid == connectionUid }) }
    }
    suspend fun pending(connectionUid: String, revision: String) = observe(connectionUid).first().filter { it.context.credentialRevision == revision && it.costUsd == null && it.generationId != null }.take(5)
    private fun decode(value: String?) = value?.let { runCatching { json.decodeFromString<List<OpenRouterReceipt>>(it) }.getOrDefault(emptyList()) }.orEmpty()
}

/** Captures final usage-only chunks and nonstream responses, even with empty choices. Never replays a generation. */
fun Flow<ChatCompletionChunk>.recordOpenRouterReceipts(context: OpenRouterReceiptContext?, store: OpenRouterReceiptStore?, captureAtDispatch: Boolean = true): Flow<ChatCompletionChunk> {
    if (context == null || store == null) return this
    return flow {
        val dispatchContext = if (captureAtDispatch) {
            try {
                store.capture(context.copy(attemptId = UUID.randomUUID().toString()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                context.copy(accountBindingRevision = null)
            }
        } else {
            context
        }
        var receipt = OpenRouterReceipt(context = dispatchContext)
        var started = System.nanoTime()
        suspend fun save() {
            withContext(NonCancellable) {
                // Billing telemetry failures must not interrupt the answer or trigger another charge.
                try {
                    val saved = store.record(receipt.copy(durationMs = (System.nanoTime() - started) / 1_000_000))
                    store.finalized(saved)
                } catch (_: Exception) { }
            }
        }
        try {
            collect { chunk ->
                if (chunk.id != null && receipt.generationId != null && chunk.id != receipt.generationId) {
                    save()
                    receipt = OpenRouterReceipt(context = dispatchContext)
                    started = System.nanoTime()
                }
                val usage = chunk.usage
                receipt = receipt.copy(
                    remoteEvidence = receipt.remoteEvidence || chunk.hasRemoteGenerationEvidence(),
                    generationId = chunk.id ?: receipt.generationId, resolvedModel = chunk.model ?: receipt.resolvedModel,
                    provider = chunk.provider ?: receipt.provider, cacheStatus = chunk.cacheStatus ?: receipt.cacheStatus,
                    costUsd = usage?.cost.decimal() ?: receipt.costUsd,
                    providerCostUsd = usage?.costDetails?.money("upstream_inference_cost") ?: receipt.providerCostUsd,
                    inputTokens = usage?.promptTokens ?: receipt.inputTokens, outputTokens = usage?.completionTokens ?: receipt.outputTokens,
                    cachedTokens = usage?.promptTokensDetails?.text("cached_tokens")?.toIntOrNull() ?: receipt.cachedTokens,
                    status = when {
                        chunk.error != null -> "failed"
                        chunk.streamFinished || chunk.choices.orEmpty().any { it.finishReason != null } -> "completed"
                        else -> receipt.status
                    }
                )
                emit(chunk)
            }
        } catch (error: CancellationException) {
            receipt = receipt.copy(status = "cancelled")
            throw error
        } catch (error: Exception) {
            receipt = receipt.copy(status = "failed")
            throw error
        } finally {
            save()
        }
    }
}

internal fun ChatCompletionChunk.hasRemoteGenerationEvidence(): Boolean = !id.isNullOrBlank() ||
    usage?.let { it.promptTokens != null || it.completionTokens != null || it.totalTokens != null || it.cost != null } == true ||
    choices.orEmpty().any { !it.delta?.content.isNullOrEmpty() || !it.message?.content.isNullOrEmpty() || !it.delta?.toolCalls.isNullOrEmpty() || !it.message?.toolCalls.isNullOrEmpty() }
