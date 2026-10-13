package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.openrouter.account.decimal
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenRouterMaxPrice(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val prompt: JsonPrimitive? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val completion: JsonPrimitive? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val image: JsonPrimitive? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val request: JsonPrimitive? = null
)

/** One policy resolver for UI previews and every synchronous execution lane. */
object OpenRouterPolicy {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    fun decodeProfile(value: String?): Pair<OpenRouterOptions?, OpenRouterProviderRouting?> {
        if (value.isNullOrBlank()) return null to null
        val objectValue = json.parseToJsonElement(value).jsonObject
        val wrapped = objectValue.keys.any { it in setOf("provider", "stream", "max_tokens", "temperature", "top_p", "top_k", "seed", "repetition_penalty") }
        return if (wrapped) {
            val options = json.decodeFromString<OpenRouterOptions>(value)
            options to (objectValue["provider"]?.takeIf { it is JsonObject }?.let { json.decodeFromString<OpenRouterProviderRouting>(it.toString()) })
        } else {
            null to json.decodeFromString<OpenRouterProviderRouting>(value)
        }
    }

    fun resolve(defaults: OpenRouterProviderRouting?, override: OpenRouterProviderRouting?): OpenRouterProviderRouting {
        val base = defaults?.normalized() ?: OpenRouterProviderRouting()
        val profile = override?.normalized() ?: OpenRouterProviderRouting()
        validate(base)
        validate(profile)
        val only = when {
            base.only.isNullOrEmpty() -> profile.only
            profile.only.isNullOrEmpty() -> base.only
            else -> intersectSlugs(base.only, profile.only).also { require(it.isNotEmpty()) { "No provider satisfies both allowlists." } }
        }
        val result = base.copy(
            sort = profile.sort ?: base.sort,
            order = profile.order ?: base.order,
            only = only,
            ignore = (base.ignore.orEmpty() + profile.ignore.orEmpty()).distinct().takeIf { it.isNotEmpty() },
            allowFallbacks = if (base.allowFallbacks == false) false else profile.allowFallbacks ?: base.allowFallbacks,
            requireParameters = if (base.requireParameters == true) true else profile.requireParameters ?: base.requireParameters,
            dataCollection = if (base.dataCollection == "deny") "deny" else profile.dataCollection ?: base.dataCollection,
            zdr = if (base.zdr == true) true else profile.zdr ?: base.zdr,
            enforceDistillableText = if (base.enforceDistillableText == true) true else profile.enforceDistillableText ?: base.enforceDistillableText,
            quantizations = when {
                base.quantizations.isNullOrEmpty() -> profile.quantizations
                profile.quantizations.isNullOrEmpty() -> base.quantizations
                else -> base.quantizations.intersect(profile.quantizations.toSet()).toList().also { require(it.isNotEmpty()) { "No shared quantization." } }
            },
            preferredMinThroughput = profile.preferredMinThroughput ?: base.preferredMinThroughput,
            preferredMaxLatency = profile.preferredMaxLatency ?: base.preferredMaxLatency,
            maxPrice = mergePrice(base.maxPrice, profile.maxPrice)
        )
        validate(result)
        return result
    }
    private fun matchesSlug(base: String, candidate: String): Boolean = base == candidate || candidate.startsWith("$base/")
    private fun intersectSlugs(a: List<String>, b: List<String>): List<String> = a.flatMap { x ->
        b.mapNotNull { y ->
            when {
                matchesSlug(x, y) -> y
                matchesSlug(y, x) -> x
                else -> null
            }
        }
    }.distinct()
    private fun mergePrice(a: OpenRouterMaxPrice?, b: OpenRouterMaxPrice?): OpenRouterMaxPrice? {
        if (a == null) return b
        if (b == null) return a
        fun min(x: JsonPrimitive?, y: JsonPrimitive?): JsonPrimitive? = listOfNotNull(x, y).minByOrNull { it.decimal()!!.toBigDecimal() }
        return OpenRouterMaxPrice(min(a.prompt, b.prompt), min(a.completion, b.completion), min(a.image, b.image), min(a.request, b.request))
    }
    fun validate(policy: OpenRouterProviderRouting) {
        require(policy.sort == null || policy.sort in setOf("price", "price-asc", "latency", "throughput")) { "Unknown routing strategy." }
        require(policy.dataCollection == null || policy.dataCollection in setOf("allow", "deny"))
        listOf(policy.only, policy.ignore, policy.order, policy.quantizations).forEach { values ->
            require(values == null || (values.size <= 100 && values.all { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }))
        }
        require(policy.only.orEmpty().none { allow -> policy.ignore.orEmpty().any { deny -> matchesSlug(deny, allow) } }) { "Allowed and excluded providers conflict." }
        listOfNotNull(policy.maxPrice?.prompt, policy.maxPrice?.completion, policy.maxPrice?.image, policy.maxPrice?.request).forEach {
            require(!it.isString && it.decimal() != null) { "Use a nonnegative decimal price ceiling." }
        }
        fun performance(value: JsonElement?) {
            if (value == null) return
            val values = if (value is JsonObject) {
                require(value.isNotEmpty() && value.keys.all { it in setOf("p50", "p75", "p90", "p99") })
                value.values
            } else {
                listOf(value)
            }
            require(values.all { it is JsonPrimitive && !it.isString && it.decimal() != null })
        }
        performance(policy.preferredMinThroughput)
        performance(policy.preferredMaxLatency)
    }
    fun batch(policy: OpenRouterProviderRouting, noRemoteStorage: Boolean): JsonObject {
        validate(policy)
        require(
            !noRemoteStorage &&
                policy.zdr != true &&
                policy.dataCollection != "deny" &&
                policy.maxPrice == null &&
                policy.requireParameters != true &&
                policy.ignore.isNullOrEmpty() &&
                policy.quantizations.isNullOrEmpty() &&
                policy.enforceDistillableText != true
        ) { "This batch cannot enforce the selected restrictions. Use synchronous execution." }
        return JsonObject(
            buildMap {
                policy.only?.takeIf { it.isNotEmpty() }?.let { put("only", kotlinx.serialization.json.JsonArray(it.map(::JsonPrimitive))) }
                policy.allowFallbacks?.let { put("allow_fallbacks", JsonPrimitive(it)) }
            }
        )
    }
    fun headers(settings: OpenRouterSettings, fresh: Boolean, hasTools: Boolean): Map<String, String> = buildMap {
        val cache = settings.responseCachingEnabled && !settings.noRemoteResponseStorage && !fresh && !hasTools
        put("X-OpenRouter-Cache", cache.toString())
        if (cache) put("X-OpenRouter-Cache-TTL", settings.responseCacheTtlSeconds.coerceIn(1, 86400).toString())
        if (settings.attributionEnabled) {
            put("HTTP-Referer", "https://github.com/tailscale-signin/GPT_Mobile_AI-improved")
            put("X-Title", "GPT Mobile AI Improved")
        }
    }
    fun session(connection: String, chat: Long, profile: String, lane: String): String = OpenRouterIdentity.digest("$connection|$chat|$profile|$lane")
    fun cacheIdentity(connection: String, endpoint: String, credentialRevision: String, model: String, request: String, policy: String): String =
        OpenRouterIdentity.digest(connection) + "_" + OpenRouterIdentity.digest("v2|$connection|$endpoint|$credentialRevision|$model|$request|$policy")
}
