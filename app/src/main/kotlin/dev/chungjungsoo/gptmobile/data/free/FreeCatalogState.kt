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

@kotlinx.serialization.Serializable
data class FreeServiceStatus(val catalogCheckSucceeded: Boolean = false, val generationFailures: Int = 0, val failureSignature: String? = null, val readiness: FreeGenerationReadiness = FreeGenerationReadiness.UNTESTED, val retryAt: Long? = null, val deniedModel: String? = null, val denialCount: Int = 0, val reachable: Boolean? = null, val checking: Boolean = false, val checkedAt: Long = 0, val message: String = "Not checked", val servedModel: String? = null, val remaining: String? = null)

/** Per-check authority. Shared UI health may change as soon as the catalog mutex unlocks. */
data class FreeCatalogRefreshResult(val models: List<FreeModelOption>, val succeeded: Boolean, val readiness: FreeGenerationReadiness = FreeGenerationReadiness.UNTESTED, val message: String = "")

object FreeCatalogState {
    private val _status = MutableStateFlow<Map<FreeAiProvider, FreeServiceStatus>>(emptyMap())
    val status = _status.asStateFlow()
    private val _routeStatus = MutableStateFlow<Map<String, FreeServiceStatus>>(emptyMap())
    val routeStatus = _routeStatus.asStateFlow()

    @kotlinx.serialization.Serializable
    private data class HealthSnapshot(val version: Int = 2, val providers: Map<String, FreeServiceStatus>, val routes: Map<String, FreeServiceStatus>)
    private fun healthPrefix(provider: FreeAiProvider, model: String) = "${provider.name}|${model.length}:$model|"
    private fun healthKey(provider: FreeAiProvider, model: String, credentialIdentity: String) = healthPrefix(provider, model) + credentialIdentity
    fun generationStatus(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown"): FreeServiceStatus? =
        _routeStatus.value[healthKey(provider, model, credentialIdentity)]
            ?: _routeStatus.value[healthKey(provider, model, "unknown")]
            ?: _status.value[provider]?.takeIf { it.deniedModel == model && _routeStatus.value.keys.none { key -> key.startsWith(healthPrefix(provider, model)) } }

    private fun updateHealth(provider: FreeAiProvider, model: String?, credentialIdentity: String, transform: (FreeServiceStatus) -> FreeServiceStatus) {
        var updated: FreeServiceStatus? = null
        if (model != null) {
            val key = healthKey(provider, model, credentialIdentity)
            _routeStatus.update { current ->
                transform(current[key] ?: FreeServiceStatus()).let { next ->
                    updated = next
                    current + (key to next)
                }
            }
        }
        // This is a catalog-screen summary only. Admission reads generationStatus for the actual route.
        _status.update { current -> current + (provider to (updated ?: transform(current[provider] ?: FreeServiceStatus()))) }
    }
    private val _catalog = MutableStateFlow<Map<FreeAiProvider, List<FreeModelOption>>>(emptyMap())
    val catalog = _catalog.asStateFlow()
    private val checks = FreeAiProvider.entries.associateWith { Mutex() }
    private val parallelChecks = Semaphore(3)
    private val disk = Mutex()
    private var cache: File? = null
    private var healthCache: File? = null
    private val recovering = mutableSetOf<String>()

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        cache = File(context.filesDir, "free-model-catalog-v1.json")
        healthCache = File(context.filesDir, "free-generation-health-v1.json")
        runCatching {
            val file = healthCache!!
            if (file.isFile && file.length() <= 1_048_576) {
                val json = Json { ignoreUnknownKeys = true }
                val text = file.readText()
                val encoded = json.parseToJsonElement(text) as JsonObject
                val snapshot = if ("providers" in encoded) json.decodeFromString<HealthSnapshot>(text) else HealthSnapshot(providers = json.decodeFromString<Map<String, FreeServiceStatus>>(text), routes = emptyMap())
                check(snapshot.version == 2)
                _status.value = snapshot.providers.mapNotNull { (name, state) -> FreeAiProvider.entries.firstOrNull { it.name == name }?.let { it to state.copy(checking = false) } }.toMap()
                _routeStatus.value = snapshot.routes.mapValues { it.value.copy(checking = false) }.ifEmpty {
                    _status.value.mapNotNull { (provider, state) -> state.deniedModel?.let { healthKey(provider, it, "unknown") to state } }.toMap()
                }
            }
        }
        runCatching {
            val saved = Json.decodeFromString<Map<String, List<FreeModelOption>>>(cache!!.readText())
            _catalog.value = saved.mapNotNull { (name, models) ->
                FreeAiProvider.entries.firstOrNull { it.name == name }?.let { provider ->
                    provider to models.filter { (provider != FreeAiProvider.LLM7 || it.freeAllowance) && FreeModelCatalog.acceptsDiscovered(provider, it.id, true) }.take(256)
                }
            }.toMap()
        }
    }

    /** A new network invalidates reachability, without clearing provider cooldowns. */
    fun networkChanged() {
        fun reset(state: FreeServiceStatus): FreeServiceStatus = if (state.readiness == FreeGenerationReadiness.UNREACHABLE) state.copy(readiness = FreeGenerationReadiness.UNTESTED, reachable = null, message = "Network changed · reachability not checked") else state
        _status.update { current ->
            current.mapValues { (_, state) -> reset(state) }
        }
        _routeStatus.update { current -> current.mapValues { (_, state) -> reset(state) } }
        saveHealth()
    }

    fun models(provider: FreeAiProvider): List<FreeModelOption> = if (provider.isRetired) {
        emptyList()
    } else if (provider == FreeAiProvider.LLM7) {
        (FreeModelCatalog.defaults(provider) + _catalog.value[provider].orEmpty().filter { it.freeAllowance }).distinctBy { it.id }
    } else {
        (_catalog.value[provider].orEmpty().map { mergeLimits(it, FreeModelCatalog.defaults(provider).firstOrNull { known -> known.id == it.id }) } + FreeModelCatalog.defaults(provider)).distinctBy { it.id }
    }
    fun model(provider: FreeAiProvider, id: String): FreeModelOption? = models(provider).firstOrNull { it.id == id }
    fun requireModel(provider: FreeAiProvider, id: String): FreeModelOption = requireNotNull(model(provider, id)) { "This model is not in ${provider.displayName}'s free catalog. Refresh models in Free Models settings." }

    fun recordAccepted(provider: FreeAiProvider) {
        _status.update { current -> current + (provider to (current[provider] ?: FreeServiceStatus()).copy(reachable = true, checkedAt = System.currentTimeMillis(), message = "Request accepted · generation pending")) }
    }

    fun recordResponse(provider: FreeAiProvider, model: String?, remaining: String? = null, credentialIdentity: String = "unknown", requestedModel: String? = model) {
        updateHealth(provider, requestedModel, credentialIdentity) { it.copy(readiness = FreeGenerationReadiness.RESPONSE_RECEIVED, generationFailures = 0, failureSignature = null, retryAt = null, denialCount = 0, deniedModel = null, reachable = true, checkedAt = System.currentTimeMillis(), message = "Response received · answer quality not verified", servedModel = model, remaining = remaining) }
        saveHealth()
    }

    @Synchronized
    private fun saveHealth() {
        runCatching {
            healthCache?.let { target ->
                val temporary = File(target.path + ".tmp")
                temporary.writeText(Json.encodeToString(HealthSnapshot(providers = _status.value.mapKeys { it.key.name }, routes = _routeStatus.value)))
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    @Synchronized
    fun recordTerminalFailure(provider: FreeAiProvider, model: String, message: String, observedAt: Long = System.currentTimeMillis(), credentialIdentity: String = "unknown") {
        val signature = dev.chungjungsoo.gptmobile.data.research.researchHash(message.take(600))
        updateHealth(provider, model, credentialIdentity) { old ->
            val count = if (old.failureSignature == signature) old.generationFailures + 1 else 1
            old.copy(
                readiness = FreeGenerationReadiness.GENERATION_FAILED,
                reachable = true,
                generationFailures = count,
                failureSignature = signature,
                deniedModel = model,
                retryAt = if (count >= 2) observedAt + (60_000L * count).coerceAtMost(300_000L) else null,
                checkedAt = observedAt,
                message = message.take(240)
            )
        }
        saveHealth()
    }

    @Synchronized
    fun generationUnavailableReason(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown"): String? {
        val state = generationStatus(provider, model, credentialIdentity) ?: return null
        if (healthKey(provider, model, credentialIdentity) in recovering) return "${provider.displayName}: a generation recovery check is already running for this model and credential."
        if (state.deniedModel == model &&
            (state.retryAt ?: 0) > System.currentTimeMillis() &&
            (state.readiness == FreeGenerationReadiness.ACCESS_DENIED || state.generationFailures >= 2)
        ) {
            return "${provider.displayName} is temporarily unavailable after repeated ${if (state.generationFailures >= 2) "generation failures" else "access denials"}. Next eligible retry: ${java.time.Instant.ofEpochMilli(state.retryAt!!)}. Choose another provider."
        }
        return null
    }

    @Synchronized
    fun beginGeneration(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown"): Boolean {
        requireGenerationReady(provider, model, credentialIdentity)
        val recovery = (generationStatus(provider, model, credentialIdentity)?.generationFailures ?: 0) >= 2
        if (recovery) recovering += healthKey(provider, model, credentialIdentity)
        return recovery
    }

    @Synchronized
    fun endGeneration(provider: FreeAiProvider, model: String? = null, credentialIdentity: String = "unknown") {
        if (model == null) {
            recovering.removeAll { it.startsWith("${provider.name}|") }
        } else {
            recovering -= healthKey(provider, model, credentialIdentity)
        }
    }

    internal fun resetForTest() {
        _status.value = emptyMap()
        _routeStatus.value = emptyMap()
        _catalog.value = emptyMap()
        recovering.clear()
        healthCache = null
    }

    fun recordFailure(provider: FreeAiProvider, message: String, readiness: FreeGenerationReadiness = FreeGenerationReadiness.GENERATION_FAILED, reachable: Boolean? = true, retryAt: Long? = null, model: String? = null, credentialIdentity: String = "unknown") {
        val observedAt = System.currentTimeMillis()
        val signature = dev.chungjungsoo.gptmobile.data.research.researchHash(message.take(600))
        updateHealth(provider, model, credentialIdentity) { old ->
            val denials = if (readiness == FreeGenerationReadiness.ACCESS_DENIED) {
                if (old.deniedModel == model && observedAt - old.checkedAt < 600_000) old.denialCount + 1 else 1
            } else {
                0
            }
            // HTTP generation failures need the same circuit breaker as queued
            // terminal failures; otherwise a recurring 502 never becomes ineligible.
            val generationFailure = readiness == FreeGenerationReadiness.GENERATION_FAILED
            val failures = if (generationFailure) {
                if (old.failureSignature == signature && observedAt - old.checkedAt < 600_000) old.generationFailures + 1 else 1
            } else {
                old.generationFailures
            }
            val localRetry = when {
                generationFailure && failures >= 2 -> observedAt + (60_000L * failures).coerceAtMost(300_000L)
                denials >= 2 -> observedAt + 60_000
                else -> null
            }
            old.copy(
                readiness = readiness,
                reachable = reachable ?: old.reachable,
                retryAt = listOfNotNull(retryAt, localRetry).maxOrNull(),
                deniedModel = model.takeIf { denials > 0 || generationFailure },
                denialCount = denials,
                generationFailures = failures,
                failureSignature = if (generationFailure) signature else old.failureSignature,
                checkedAt = observedAt,
                message = message.take(240)
            )
        }
        saveHealth()
    }

    fun recordHttpFailure(provider: FreeAiProvider, status: Int, message: String, retryAt: Long? = null, model: String? = null, body: String = "", credentialIdentity: String = "unknown") = recordFailure(
        provider,
        message,
        when {
            provider == FreeAiProvider.POLLINATIONS && status == 402 && retryAt != null -> FreeGenerationReadiness.RATE_LIMITED
            dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(status, body).kind == dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind.BUDGET -> FreeGenerationReadiness.ALLOWANCE_RESTRICTED
            status == 429 -> FreeGenerationReadiness.RATE_LIMITED
            status in setOf(401, 402, 403) -> FreeGenerationReadiness.ACCESS_DENIED
            status in setOf(400, 413, 415, 422) -> FreeGenerationReadiness.INVALID_REQUEST
            else -> FreeGenerationReadiness.GENERATION_FAILED
        },
        reachable = true,
        retryAt = retryAt,
        model = model,
        credentialIdentity = credentialIdentity
    )

    fun requireGenerationReady(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown") {
        generationUnavailableReason(provider, model, credentialIdentity)?.let { throw FreeGenerationUnavailableException(it) }
    }

    suspend fun refresh(provider: FreeAiProvider, force: Boolean = false, token: String? = null, catalogReader: (suspend () -> kotlinx.serialization.json.JsonElement)? = null) = checks.getValue(provider).withLock {
        if (provider.isRetired) return@withLock FreeCatalogRefreshResult(emptyList(), false, message = provider.unavailableReason.orEmpty())
        if (!provider.isAvailable || provider.requiresUserToken && token.isNullOrBlank()) {
            val message = provider.unavailableReason ?: "Add API token in an LLM7 profile to check its catalog."
            _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(catalogCheckSucceeded = false, message = message)) }
            return@withLock FreeCatalogRefreshResult(emptyList(), false, message = message)
        }
        if (!force && System.currentTimeMillis() - (_status.value[provider]?.checkedAt ?: 0) < 300_000) {
            val status = _status.value.getValue(provider)
            return@withLock FreeCatalogRefreshResult(models(provider), status.catalogCheckSucceeded, status.readiness, status.message)
        }
        _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(checking = true, catalogCheckSucceeded = false)) }
        try {
            parallelChecks.withPermit {
                val payload = catalogReader?.invoke() ?: withContext(Dispatchers.IO) { readCatalog(provider, token) }
                val models = decodeModels(provider, payload)
                check(provider == FreeAiProvider.LLM7 && (payload as? JsonObject)?.get("data") is JsonArray || models.isNotEmpty() || provider == FreeAiProvider.HUGGING_FACE && (payload as? JsonObject)?.containsKey("named_endpoints") == true) { "Service responded without a compatible model catalog" }
                var currentModels = emptyList<FreeModelOption>()
                _catalog.update { old ->
                    val reviewedIds = if (provider == FreeAiProvider.LLM7) emptySet() else FreeModelCatalog.defaults(provider).map { it.id }.toSet()
                    val merged = models.map { mergeLimits(it, old[provider]?.firstOrNull { previous -> previous.id == it.id }) }
                    // Preserve learned lower capacities in this request's snapshot,
                    // while live metadata alone determines model membership/access.
                    currentModels = merged
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
                _status.update { it + (provider to (it[provider] ?: FreeServiceStatus()).copy(catalogCheckSucceeded = true, reachable = true, checkedAt = System.currentTimeMillis(), message = (it[provider]?.message?.takeIf { _ -> it[provider]?.readiness != FreeGenerationReadiness.UNTESTED } ?: "Catalog reachable · generation not tested"))) }
                FreeCatalogRefreshResult(currentModels.toList(), true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if ((_status.value[provider]?.generationFailures ?: 0) >= 2) {
                _status.update { it + (provider to it.getValue(provider).copy(message = "Catalog check failed; generation remains unavailable")) }
            } else if (error is CatalogHttpFailure) {
                val message = if (provider.acceptsUserToken && error.status in setOf(401, 403)) {
                    if (token.isNullOrBlank()) "Anonymous catalog access was declined. Add a free API token from https://dash.llm7.io or retry later (HTTP ${error.status})." else "Check API token: catalog returned HTTP ${error.status}"
                } else {
                    "Catalog returned HTTP ${error.status}"
                }
                recordHttpFailure(provider, error.status, message)
            } else {
                recordFailure(provider, error.message ?: "Service unreachable from this device", if (error is java.io.IOException) FreeGenerationReadiness.UNREACHABLE else FreeGenerationReadiness.INVALID_REQUEST, reachable = error !is java.io.IOException)
            }
            val status = _status.value[provider] ?: FreeServiceStatus()
            FreeCatalogRefreshResult(emptyList(), false, status.readiness, status.message)
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

    internal fun readCatalog(provider: FreeAiProvider, token: String?, openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): kotlinx.serialization.json.JsonElement {
        val connection = openConnection(URL(catalogUrl(provider)))
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            if (provider.acceptsUserToken && !token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
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
            if (provider != FreeAiProvider.LLM7 && prices.any { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0) > 0.0 }) return@mapNotNull null
            val llm7Free = (item["model_type"] as? JsonPrimitive)?.contentOrNull == "chat" && (item["tier"] as? JsonPrimitive)?.contentOrNull == "turbo" && (item["usage_based_only"] as? JsonPrimitive)?.booleanOrNull == false
            if (provider == FreeAiProvider.LLM7 && !llm7Free) return@mapNotNull null
            val free = llm7Free || prices.size >= 2 && prices.all { (it as? JsonPrimitive)?.doubleOrNull == 0.0 }
            if (!FreeModelCatalog.acceptsDiscovered(provider, id, free || (item["isFree"] as? JsonPrimitive)?.booleanOrNull == true || (item["billing_mode"] as? JsonPrimitive)?.contentOrNull == "free")) return@mapNotNull null
            if (provider == FreeAiProvider.OVHCLOUD && ((item["context_length"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) <= 0) return@mapNotNull null
            val parameters = (item["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val known = FreeModelCatalog.defaults(provider).firstOrNull { it.id == id }
            val topProvider = item["top_provider"] as? JsonObject
            fun capacity(vararg fields: String): Int? = fields.mapNotNull { field ->
                ((item[field] as? JsonObject)?.get("tokens") as? JsonPrimitive ?: (item[field] ?: topProvider?.get(field)) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 1..100_000_000 }
            }.minOrNull()
            mergeLimits(FreeModelOption(id, (item["name"] as? JsonPrimitive)?.contentOrNull ?: known?.label ?: id, tools = if (provider == FreeAiProvider.KILO) "tools" in parameters else known?.tools == true, privacy = known?.privacy, freeAllowance = llm7Free, streaming = if (provider == FreeAiProvider.LLM7) (item["stream"] as? JsonPrimitive)?.booleanOrNull == true else provider !in setOf(FreeAiProvider.AI_HORDE, FreeAiProvider.HUGGING_FACE), contextTokens = capacity("context_length", "context_window", "max_model_len"), outputTokens = capacity("max_output_tokens", "max_completion_tokens", "max_output_length"), reasoning = (item["reasoning"] as? JsonPrimitive)?.booleanOrNull), known)
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

internal class CatalogHttpFailure(val status: Int) : IllegalStateException("Catalog HTTP $status")

internal class FreeGenerationUnavailableException(message: String) : dev.chungjungsoo.gptmobile.data.network.FreeProviderException(dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory.PROVIDER_UNAVAILABLE, message)
