package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.providerErrorDetails
import dev.chungjungsoo.gptmobile.data.network.providerRequestIdentity
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Per-flow state. No prompt, response, tools or credentials are retained. */
class FreeRequestDiagnostics {
    internal val failureId = java.util.UUID.randomUUID().toString()
    internal var requestSent: Boolean? = false
    internal var attemptedDispatch = false
    internal var responseReceived = false
    internal var network = dev.chungjungsoo.gptmobile.data.network.DeviceNetworkDiagnostics.episodes.current()

    internal fun attempted() {
        attemptedDispatch = true
        requestSent = null // Attempting dispatch does not establish that DNS or transmission succeeded.
        responseReceived = false
        category = null
        cooldown = null
        network = dev.chungjungsoo.gptmobile.data.network.DeviceNetworkDiagnostics.episodes.current()
    }

    internal fun responded() {
        requestSent = true
        responseReceived = true
    }

    internal fun dnsFailed(host: String) {
        requestSent = false
        network = dev.chungjungsoo.gptmobile.data.network.DeviceNetworkDiagnostics.episodes.dnsFailure(host, network)
    }
    internal var category: dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory? = null
    internal var cooldown: dev.chungjungsoo.gptmobile.data.network.FreeCooldown? = null
    internal val startedAt = System.currentTimeMillis()
    internal var admission: RequestAdmissionReceipt? = null
    internal var recorded = false
    internal var stage = "preparation"
    internal var jobId: String? = null
    internal var progress: String? = null
    internal var terminal: Boolean? = null
}

@Serializable
data class FreeProviderFailureRecord(
    val time: Long,
    val failureId: String? = null,
    val category: String? = null,
    val action: String? = null,
    val requestSent: Boolean? = null,
    val attemptedDispatch: Boolean = false,
    val connectionEstablished: Boolean? = null,
    val requestTransmitted: Boolean? = null,
    val responseReceived: Boolean = false,
    val networkChangeId: String? = null,
    val networkValidation: String? = null,
    val networkEpisodeId: String? = null,
    val networkEpisodeHostCount: Int = 0,
    val cooldownOrigin: dev.chungjungsoo.gptmobile.data.network.FreeCooldown? = null,
    val provider: String,
    val endpoint: String,
    val model: String,
    val profileUid: String?,
    val chatId: Int?,
    val stage: String,
    val status: Int? = null,
    val contentType: String? = null,
    val responseFormat: String = "none",
    val code: String? = null,
    val message: String,
    val requestId: String? = null,
    val retryAt: Long? = null,
    val resetHeaders: Map<String, String> = emptyMap(),
    val quotaScope: String? = null,
    val jobId: String? = null,
    val progress: String? = null,
    val event: String? = null,
    val terminal: Boolean? = null,
    val promptTokens: Long? = null,
    val outputTokens: Long? = null,
    val safetyTokens: Long? = null,
    val contextTokens: Long? = null
)

/** Small independent failure history, also included in exports when verbose logging was off. */
object FreeProviderDiagnostics {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var file: File? = null
    private var loaded = false
    private val revokedChats = mutableMapOf<Int, Long>()
    private var records = emptyList<FreeProviderFailureRecord>()

    @Synchronized fun initialize(target: File) {
        file = target
        loaded = false
        records = emptyList()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        records = runCatching { file?.takeIf { it.isFile && it.length() <= 256_000 }?.let { json.decodeFromString<List<FreeProviderFailureRecord>>(it.readText()) }.orEmpty().takeLast(64) }.getOrDefault(emptyList())
    }

    @Synchronized fun snapshot(chatId: Int? = null): List<FreeProviderFailureRecord> {
        load()
        return records.filter { chatId == null || it.chatId == chatId }
    }

    @Synchronized fun clear() {
        records = emptyList()
        loaded = true
        file?.delete()
        file?.let { File(it.path + ".tmp").delete() }
    }

    @Synchronized fun forgetChat(chatId: Int) {
        revokedChats[chatId] = System.currentTimeMillis()
        load()
        records = records.filterNot { it.chatId == chatId }
        save()
    }

    private fun save() {
        runCatching {
            file?.let { target ->
                target.parentFile?.mkdirs()
                val temporary = File(target.path + ".tmp")
                temporary.writeText(json.encodeToString(records))
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    fun recordPreflight(profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2, outcome: dev.chungjungsoo.gptmobile.data.network.FreePreflightOutcome, chatId: Int?, persistent: Boolean) {
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(profile.apiUrl) ?: return
        val diagnostics = FreeRequestDiagnostics().apply {
            category = outcome.category
            cooldown = outcome.cooldown
            stage = "preflight"
        }
        record(
            ProviderRequestConfig(
                profile.apiUrl,
                profile.token,
                freeProvider = provider,
                profileUid = profile.uid,
                queueScope = chatId?.toString(),
                queuePersistent = persistent,
                freeDiagnostics = diagnostics
            ),
            profile.model,
            outcome.message,
            retryAt = outcome.retryAt
        )
    }

    @Synchronized internal fun record(config: ProviderRequestConfig, model: String, message: String, status: Int? = null, body: String = "", header: (String) -> String? = { null }, retryAt: Long? = null, event: String? = null, terminal: Boolean? = null) {
        val provider = config.freeProvider ?: return
        val state = config.freeDiagnostics
        if (status != null) state?.responded()
        state?.recorded = true
        // Temporary chats must not create persistent or exportable failure history.
        if (!config.queuePersistent) return
        val chatId = config.queueScope?.substringBefore(':')?.toIntOrNull()
        if ((state?.startedAt ?: 0) <= (revokedChats[chatId] ?: Long.MIN_VALUE)) return
        load()
        val root = runCatching { Json.parseToJsonElement(body.take(16_384)) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject ?: root
        val code = ((error?.get("code") ?: error?.get("rc")) as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.:-]{0,79}")) }
        val contentType = header("Content-Type")?.substringBefore(';')?.take(80)
        val html = contentType?.contains("html", true) == true || body.trimStart().startsWith('<')
        val receipt = state?.admission
        val record = FreeProviderFailureRecord(
            time = System.currentTimeMillis(),
            failureId = state?.failureId, category = state?.category?.name, action = state?.category?.action,
            attemptedDispatch = state?.attemptedDispatch == true,
            connectionEstablished = if (state?.responseReceived == true) true else false.takeIf { state?.category == dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory.DNS_RESOLUTION_FAILED },
            requestTransmitted = state?.requestSent,
            responseReceived = state?.responseReceived == true,
            networkChangeId = state?.network?.changeId,
            networkValidation = state?.network?.validation,
            networkEpisodeId = state?.network?.episodeId,
            networkEpisodeHostCount = state?.network?.distinctHosts ?: 0,
            requestSent = state?.requestSent, cooldownOrigin = state?.cooldown,
            provider = provider.name, endpoint = provider.apiUrl, model = model.take(256), profileUid = config.profileUid,
            chatId = config.queueScope?.substringBefore(':')?.toIntOrNull(), stage = state?.stage ?: "unknown", status = status,
            contentType = contentType,
            responseFormat = if (html) {
                "html"
            } else if (root != null) {
                "json"
            } else if (body.isBlank() || body.trim() == "null") {
                "empty"
            } else {
                "text"
            },
            code = code, message = if (html) message.take(600) else providerErrorDetails(body, message, config.token).take(600),
            requestId = providerRequestIdentity(header).takeUnless { it == "unavailable" }, retryAt = retryAt,
            resetHeaders = listOf("Retry-After", "X-RateLimit-Reset", "X-RateLimit-Reset-Tokens", "X-RateLimit-Reset-Requests", "RateLimit-Reset", "X-RateLimit-Scope").mapNotNull { name ->
                header(name)?.takeIf { it.length <= 120 && it.matches(Regex("[A-Za-z0-9 ,.:/_-]+")) }?.let { name to it }
            }.toMap(),
            quotaScope = ((error?.get("quota_scope") ?: error?.get("scope")) as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/ -]{1,80}")) },
            jobId = state?.jobId, progress = state?.progress, event = event, terminal = terminal,
            promptTokens = receipt?.promptTokens, outputTokens = receipt?.outputTokens, safetyTokens = receipt?.safetyTokens, contextTokens = receipt?.contextTokens
        )
        records = (records + record).takeLast(64)
        save()
    }
}
