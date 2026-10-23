package dev.chungjungsoo.gptmobile.data.free

import android.content.Context
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Reachability is not a promise that inference or every model works. No user prompts are sent by checks. */
enum class FreeGenerationReadiness(val label: String) {
    UNTESTED("Generation not tested"),
    INVALID_REQUEST("Reachable — request invalid"),
    RATE_LIMITED("Rate limited"),
    ACCESS_DENIED("Access denied"),
    ALLOWANCE_RESTRICTED("Allowance restricted"),
    GENERATION_FAILED("Generation failed"),
    RESPONSE_RECEIVED("Response received"),
    UNREACHABLE("Unreachable")
}

data class FreeServiceStatus(val readiness: FreeGenerationReadiness = FreeGenerationReadiness.UNTESTED, val retryAt: Long? = null, val deniedModel: String? = null, val denialCount: Int = 0, val reachable: Boolean? = null, val checking: Boolean = false, val checkedAt: Long = 0, val message: String = "Not checked", val servedModel: String? = null, val remaining: String? = null)

object FreeCatalogState {
    private val _status = MutableStateFlow<Map<FreeAiProvider, FreeServiceStatus>>(emptyMap())
    val status = _status.asStateFlow()
    private val _catalog = MutableStateFlow<Map<FreeAiProvider, List<FreeModelOption>>>(emptyMap())
    val catalog = _catalog.asStateFlow()
    private val checks = FreeAiProvider.entries.associateWith { Mutex() }
    private val parallelChecks = Semaphore(3)
    private val disk = Mutex()
    private var cache: File? = null

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        cache = File(context.filesDir, "free-model-catalog-v1.json")
        runCatching {
            val saved = Json.decodeFromString<Map<String, List<FreeModelOption>>>(cache!!.readText())
            _catalog.value = saved.mapNotNull { (name, models) ->
                FreeAiProvider.entries.firstOrNull { it.name == name }?.let { provider ->
                    provider to models.filter { FreeModelCatalog.acceptsDiscovered(provider, it.id, true) }.take(256)
                }
            }.toMap()
        }
    }

    fun models(provider: FreeAiProvider): List<FreeModelOption> = (_catalog.value[provider].orEmpty().map { mergeLimits(it, FreeModelCatalog.defaults(provider).firstOrNull { known -> known.id == it.id }) } + FreeModelCatalog.defaults(provider)).distinctBy { it.id }
    fun model(provider: FreeAiProvider, id: String): FreeModelOption? = models(provider).firstOrNull { it.id == id }
    fun requireModel(provider: FreeAiProvider, id: String): FreeModelOption = requireNotNull(model(provider, id)) { "This model is not in ${provider.displayName}'s free catalog. Refresh models in Free Models settings." }

    fun recordAccepted(provider: FreeAiProvider) {
        _status.update { current -> current + (provider to (current[provider] ?: FreeServiceStatus()).copy(reachable = true, readiness = FreeGenerationReadiness.UNTESTED, checkedAt = System.currentTimeMillis(), message = "Request accepted · generation pending", denialCount = 0, deniedModel = null, retryAt = null)) }
    }

    fun recordResponse(provider: FreeAiProvider, model: String?, remaining: String? = null) {
        _status.update { current -> current + (provider to (current[provider] ?: FreeServiceStatus()).copy(readiness = FreeGenerationReadiness.RESPONSE_RECEIVED, retryAt = null, denialCount = 0, deniedModel = null, reachable = true, checkedAt = System.currentTimeMillis(), message = "Response received · answer quality not verified", servedModel = model, remaining = remaining)) }
    }

    fun recordFailure(provider: FreeAiProvider, message: String, readiness: FreeGenerationReadiness = FreeGenerationReadiness.GENERATION_FAILED, reachable: Boolean? = true, retryAt: Long? = null, model: String? = null) {
        _status.update { current ->
            val old = current[provider] ?: FreeServiceStatus()
            val denials = if (readiness == FreeGenerationReadiness.ACCESS_DENIED) {
                if (old.deniedModel == model && System.currentTimeMillis() - old.checkedAt < 600_000) old.denialCount + 1 else 1
            } else {
                0
            }
            current + (provider to old.copy(readiness = readiness, reachable = reachable ?: old.reachable, retryAt = retryAt ?: (System.currentTimeMillis() + 60_000).takeIf { denials >= 2 }, deniedModel = model.takeIf { denials > 0 }, denialCount = denials, checkedAt = System.currentTimeMillis(), message = message.take(240)))
        }
    }

    fun recordHttpFailure(provider: FreeAiProvider, status: Int, message: String, retryAt: Long? = null, model: String? = null, body: String = "") = recordFailure(
        provider,
        message,
        when {
            dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(status, body).kind == dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind.BUDGET -> FreeGenerationReadiness.ALLOWANCE_RESTRICTED
            status == 429 -> FreeGenerationReadiness.RATE_LIMITED
            status in setOf(401, 402, 403) -> FreeGenerationReadiness.ACCESS_DENIED
            status in setOf(400, 413, 415, 422) -> FreeGenerationReadiness.INVALID_REQUEST
            else -> FreeGenerationReadiness.GENERATION_FAILED
        },
        reachable = true,
        retryAt = retryAt,
        model = model
    )

    fun requireGenerationReady(provider: FreeAiProvider, model: String) {
        val state = _status.value[provider] ?: return
        if (state.readiness == FreeGenerationReadiness.ACCESS_DENIED && state.deniedModel == model && (state.retryAt ?: 0) > System.currentTimeMillis()) {
            throw FreeGenerationUnavailableException("${provider.displayName} is temporarily unavailable after repeated access denials. Choose another Free provider or retry after ${java.time.Instant.ofEpochMilli(state.retryAt!!)}.")
        }
    }

    suspend fun refresh(provider: FreeAiProvider, force: Boolean = false) = checks.getValue(provider).withLock {
        if (!force && System.currentTimeMillis() - (_status.value[provider]?.checkedAt ?: 0) < 300_000) return@withLock
        _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(checking = true)) }
        try {
            parallelChecks.withPermit {
                val payload = withContext(Dispatchers.IO) { readCatalog(provider) }
                val models = decodeModels(provider, payload)
                check(models.isNotEmpty() || provider == FreeAiProvider.HUGGING_FACE && (payload as? JsonObject)?.containsKey("named_endpoints") == true) { "Service responded without a compatible model catalog" }
                _catalog.update { old ->
                    val reviewedIds = FreeModelCatalog.defaults(provider).map { it.id }.toSet()
                    val merged = models.map { mergeLimits(it, old[provider]?.firstOrNull { previous -> previous.id == it.id }) }
                    old + (provider to (merged + old[provider].orEmpty().filter { it.id in reviewedIds }).distinctBy { it.id })
                }
                disk.withLock {
                    withContext(Dispatchers.IO) {
                        cache?.let { file ->
                            val atomic = android.util.AtomicFile(file)
                            val stream = atomic.startWrite()
                            try {
                                stream.write(Json.encodeToString(_catalog.value.mapKeys { it.key.name }).toByteArray())
                                atomic.finishWrite(stream)
                            } catch (error: Exception) {
                                atomic.failWrite(stream)
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("FreeModels", "CATALOG_CACHE_FAILED · ${error.javaClass.simpleName}")
                            }
                        }
                    }
                }
                _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(reachable = true, checkedAt = System.currentTimeMillis(), message = (it[provider]?.message?.takeIf { _ -> it[provider]?.readiness != FreeGenerationReadiness.UNTESTED } ?: "Catalog reachable · generation not tested"))) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is CatalogHttpFailure) recordHttpFailure(provider, error.status, "Catalog returned HTTP ${error.status}") else recordFailure(provider, error.message ?: "Service unreachable from this device", if (error is java.io.IOException) FreeGenerationReadiness.UNREACHABLE else FreeGenerationReadiness.INVALID_REQUEST, reachable = error !is java.io.IOException)
        } finally {
            _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(checking = false)) }
        }
    }

    internal fun catalogUrl(provider: FreeAiProvider): String = when (provider) {
        FreeAiProvider.AI_HORDE -> "${provider.apiUrl}/status/models?type=text"
        FreeAiProvider.HUGGING_FACE -> "${provider.apiUrl}/gradio_api/info"
        FreeAiProvider.OPENCODE -> "https://opencode.ai/inference/v1/models"
        else -> "${provider.apiUrl}/models"
    }

    private fun readCatalog(provider: FreeAiProvider): kotlinx.serialization.json.JsonElement {
        val connection = URL(catalogUrl(provider)).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) throw CatalogHttpFailure(connection.responseCode)
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(body.size <= 2_000_000) { "Model catalog exceeds the supported size" }
            return Json.parseToJsonElement(body.decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    internal fun decodeModels(provider: FreeAiProvider, payload: kotlinx.serialization.json.JsonElement): List<FreeModelOption> {
        val items = payload as? JsonArray ?: (payload as? JsonObject)?.let { it["data"] as? JsonArray ?: it["models"] as? JsonArray } ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = ((item["id"] ?: item["name"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val pricing = item["pricing"] as? JsonObject
            val prices = pricing?.filterKeys { it in setOf("prompt", "completion", "input", "output", "request") }?.values.orEmpty()
            if (prices.any { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0) > 0.0 }) return@mapNotNull null
            val free = prices.size >= 2 && prices.all { (it as? JsonPrimitive)?.doubleOrNull == 0.0 }
            if (!FreeModelCatalog.acceptsDiscovered(provider, id, free || (item["isFree"] as? JsonPrimitive)?.booleanOrNull == true || (item["billing_mode"] as? JsonPrimitive)?.contentOrNull == "free")) return@mapNotNull null
            if (provider == FreeAiProvider.OVHCLOUD && ((item["context_length"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) <= 0) return@mapNotNull null
            val parameters = (item["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val known = FreeModelCatalog.defaults(provider).firstOrNull { it.id == id }
            val topProvider = item["top_provider"] as? JsonObject
            fun capacity(vararg fields: String): Int? = fields.mapNotNull { field ->
                ((item[field] ?: topProvider?.get(field)) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 1..100_000_000 }
            }.minOrNull()
            mergeLimits(FreeModelOption(id, (item["name"] as? JsonPrimitive)?.contentOrNull ?: id, tools = if (provider == FreeAiProvider.KILO) "tools" in parameters else known?.tools == true, privacy = known?.privacy, streaming = provider !in setOf(FreeAiProvider.AI_HORDE, FreeAiProvider.HUGGING_FACE, FreeAiProvider.POLLINATIONS), contextTokens = capacity("context_length", "context_window", "max_model_len"), outputTokens = capacity("max_output_tokens", "max_completion_tokens", "max_output_length")), known)
        }.distinctBy { it.id }.take(256)
    }
    internal fun mergeLimits(model: FreeModelOption, previous: FreeModelOption?): FreeModelOption = model.copy(
        contextTokens = listOfNotNull(model.contextTokens, previous?.contextTokens).filter { it > 0 }.minOrNull(),
        outputTokens = listOfNotNull(model.outputTokens, previous?.outputTokens).filter { it > 0 }.minOrNull()
    )

    /** A rejection learns total context separately from output-only ceilings. */
    suspend fun learnContextLimit(provider: FreeAiProvider, id: String, tokens: Int) {
        require(tokens in 1..100_000_000)
        val old = requireModel(provider, id)
        val learned = old.copy(contextTokens = minOf(old.contextTokens ?: tokens, tokens))
        _catalog.update { current -> current + (provider to (listOf(learned) + current[provider].orEmpty()).distinctBy { it.id }) }
        disk.withLock {
            withContext(Dispatchers.IO) {
                cache?.let { file ->
                    val atomic = android.util.AtomicFile(file)
                    runCatching {
                        val stream = atomic.startWrite()
                        try {
                            stream.write(Json.encodeToString(_catalog.value.mapKeys { it.key.name }).toByteArray())
                            atomic.finishWrite(stream)
                        } catch (error: Exception) {
                            atomic.failWrite(stream)
                            throw error
                        }
                    }
                }
            }
        }
    }
}

private class CatalogHttpFailure(val status: Int) : IllegalStateException("Catalog HTTP $status")

internal class FreeGenerationUnavailableException(message: String) : IllegalStateException(message)
