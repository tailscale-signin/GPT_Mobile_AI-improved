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
data class FreeServiceStatus(val reachable: Boolean? = null, val checking: Boolean = false, val checkedAt: Long = 0, val message: String = "Not checked", val servedModel: String? = null, val remaining: String? = null)

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

    fun models(provider: FreeAiProvider): List<FreeModelOption> = (_catalog.value[provider].orEmpty() + FreeModelCatalog.defaults(provider)).distinctBy { it.id }
    fun model(provider: FreeAiProvider, id: String): FreeModelOption? = models(provider).firstOrNull { it.id == id }
    fun requireModel(provider: FreeAiProvider, id: String): FreeModelOption = requireNotNull(model(provider, id)) { "This model is not in ${provider.displayName}'s free catalog. Refresh models in Free Models settings." }

    fun recordResponse(provider: FreeAiProvider, model: String?, remaining: String? = null) {
        _status.update { current -> current + (provider to (current[provider] ?: FreeServiceStatus()).copy(reachable = true, checkedAt = System.currentTimeMillis(), message = "Inference responded", servedModel = model, remaining = remaining)) }
    }

    fun recordFailure(provider: FreeAiProvider, message: String) {
        _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(reachable = false, checkedAt = System.currentTimeMillis(), message = message.take(240))) }
    }

    suspend fun refresh(provider: FreeAiProvider, force: Boolean = false) = checks.getValue(provider).withLock {
        if (!force && System.currentTimeMillis() - (_status.value[provider]?.checkedAt ?: 0) < 300_000) return@withLock
        _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(checking = true)) }
        try {
            parallelChecks.withPermit {
                val payload = withContext(Dispatchers.IO) { readCatalog(provider) }
                val models = decodeModels(provider, payload)
                check(models.isNotEmpty() || provider == FreeAiProvider.HUGGING_FACE && (payload as? JsonObject)?.containsKey("named_endpoints") == true) { "Service responded without a compatible model catalog" }
                _catalog.update { it + (provider to models) }
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
                _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(reachable = true, checkedAt = System.currentTimeMillis(), message = "Catalog reachable · generation not tested")) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(provider, error.message ?: "Service unreachable from this device")
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
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode} · unavailable from this device" }
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
            FreeModelOption(id, (item["name"] as? JsonPrimitive)?.contentOrNull ?: id, tools = if (provider == FreeAiProvider.KILO) "tools" in parameters else known?.tools == true, privacy = known?.privacy, streaming = provider !in setOf(FreeAiProvider.AI_HORDE, FreeAiProvider.HUGGING_FACE, FreeAiProvider.POLLINATIONS))
        }.distinctBy { it.id }.take(256)
    }
}
