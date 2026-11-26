package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogSnapshotStore
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelsResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class OpenRouterModelRepository internal constructor(
    private val snapshotStore: OpenRouterCatalogSnapshotStore? = null
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        OpenRouterCatalogSnapshotStore(File(context.filesDir, "openrouter/public-capabilities-v1.json"))
    )

    private var restoredSnapshot = false

    /** A missing picker/idle warmup must not turn a known public alias into an unknown 4K route. */
    internal suspend fun prepareRequestLimits(provider: String, endpoint: String?, model: String, routing: String?, allowRefresh: Boolean): OpenRouterCatalogLimits.Limits? {
        if (provider != "OPENROUTER" || !OpenRouterCatalogLimits.officialEndpoint(endpoint)) return null
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!restoredSnapshot) {
                    restoredSnapshot = true
                    val now = System.currentTimeMillis()
                    snapshotStore?.read(now)?.let { OpenRouterCatalogLimits.restore(it.models, it.observedAtMillis, now) }
                }
            }
        }
        OpenRouterCatalogLimits.forRequest(provider, endpoint, model, routing)?.let { return it }
        if (allowRefresh) fetchModels()
        return OpenRouterCatalogLimits.forRequest(provider, endpoint, model, routing)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedModels: List<OpenRouterModelItem>? = null

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var cachedAt = 0L

    @Volatile var sourceValidatedAt: Long = 0L
        private set
    private var failedAt = Long.MIN_VALUE

    fun peekModels(): List<OpenRouterModelItem>? = cachedModels

    suspend fun fetchModels(forceRefresh: Boolean = false): Result<List<OpenRouterModelItem>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            cachedModels?.takeIf { now - cachedAt in 0..(if (forceRefresh) 30_000 else 3_600_000) }?.let {
                OpenRouterCatalogLimits.restore(it, sourceValidatedAt)
                return@withLock Result.success(it)
            }

            if (!forceRefresh && failedAt != Long.MIN_VALUE && now - failedAt in 0..30_000) {
                return@withLock Result.failure(IllegalStateException("OpenRouter catalog is cooling down after a failed refresh."))
            }
            runCatching {
                val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "GPTMobile/1.0")
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Failed to fetch OpenRouter models: HTTP $responseCode")
                    }

                    val responseBody = connection.inputStream.use { dev.chungjungsoo.gptmobile.data.network.BoundedResponseText.read(it, 8 * 1024 * 1024) }
                    val sorted = json.decodeFromString<OpenRouterModelsResponse>(responseBody)
                        .data
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.id })
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(sorted.isNotEmpty()) { "Empty OpenRouter catalogue; previous prices retained." }
                    cachedModels = sorted
                    sourceValidatedAt = System.currentTimeMillis()
                    cachedAt = System.nanoTime() / 1_000_000
                    failedAt = Long.MIN_VALUE
                    OpenRouterCatalogLimits.remember(sorted, sourceValidatedAt)
                    // A cache write failure does not invalidate the fetched public metadata.
                    runCatching { snapshotStore?.write(sorted, sourceValidatedAt) }
                    sorted
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                failedAt = System.nanoTime() / 1_000_000
            }
        }
    }

    private data class UserCatalog(val models: List<OpenRouterModelItem>, val observedAt: Long, val monotonicAt: Long)
    private val userCatalogs = linkedMapOf<String, UserCatalog>()
    private val userLock = kotlinx.coroutines.sync.Mutex()
    suspend fun fetchUserModels(connectionUid: String, endpoint: String, credential: String, forceRefresh: Boolean = false): Result<Pair<List<OpenRouterModelItem>, Long>> = userLock.withLock {
        val identity = dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity.revision(connectionUid, endpoint, credential)
        val now = System.nanoTime()
        userCatalogs[identity]?.takeIf { now - it.monotonicAt in 0..(if (forceRefresh) 10_000_000_000L else 900_000_000_000L) }?.let {
            return@withLock Result.success(it.models to it.observedAt)
        }
        try {
            val body = dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient().get(endpoint, credential, "models/user")
            val models = json.decodeFromString<OpenRouterModelsResponse>(body).data.sortedBy { it.name ?: it.id }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val entry = UserCatalog(models, System.currentTimeMillis(), System.nanoTime())
            userCatalogs[identity] = entry
            while (userCatalogs.size > 8) userCatalogs.remove(userCatalogs.keys.first())
            Result.success(models to entry.observedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val MODELS_URL = OpenRouterCatalogSnapshotStore.PUBLIC_SOURCE
    }
}
