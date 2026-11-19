package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Managed/signed evidence or an explicitly confirmed, device-local personal setup.
 * Personal evidence is accepted only from ProviderApprovalSource's nonportable store.
 * User-editable profile settings, imports and chat payloads cannot issue an approval.
 */
@Serializable
internal data class ProviderApproval(
    val endpoint: String,
    val model: String,
    val credentialSha256: String,
    val upstream: String,
    val policyUrl: String,
    val verifiedAtMillis: Long,
    val expiresAtMillis: Long,
    val feature: String,
    val retentionTier: String,
    val noTraining: Boolean,
    val loggingDisabled: Boolean,
    val externalToolsDisabled: Boolean,
    val providerDefaultsDisabled: Boolean,
    val safetyRetentionExcluded: Boolean,
    val guardrailId: String? = null,
    val providerKeyHash: String? = null,
    val allowGuardrailTightening: Boolean = false,
    val policySha256: String = "",
    val policyDocument: String = policyUrl,
    val scope: String = "exact-route-v1",
    val personalWorkspaceId: String? = null,
    val setupId: String? = null,
    val modelAvailabilityPolicyId: String? = null,
    val personalPolicyAssignment: String = "direct"
) {
    fun permitsDocument(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        val terms = java.net.URI(policyUrl)
        val trusted = when (terms.host) {
            "ai.google.dev", "docs.cloud.google.com" -> setOf("ai.google.dev", "aistudio.google.com", "docs.cloud.google.com", "cloud.google.com")
            "docs.ovhcloud.com" -> setOf("docs.ovhcloud.com", "help.ovhcloud.com")
            else -> setOf(terms.host)
        }
        uri.scheme == "https" && uri.host in trusted && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    fun covers(profile: PlatformV2, url: String, terms: String, now: Long): Boolean =
        feature == "stateless-text-v1" &&
            retentionTier == "zdr" &&
            noTraining &&
            loggingDisabled &&
            externalToolsDisabled &&
            providerDefaultsDisabled &&
            safetyRetentionExcluded &&
            verifiedAtMillis in (now - 86_400_000L)..now &&
            expiresAtMillis in (now + 1)..(verifiedAtMillis + 86_400_000L) &&
            endpoint == url &&
            (
                scope == "exact-route-v1" &&
                    model == profile.model &&
                    validUpstream(upstream) ||
                    (scope == "openrouter-account-v1" || validPersonalScope()) &&
                    profile.compatibleType == ClientType.OPENROUTER &&
                    profile.apiUrl.trimEnd('/') in OpenRouterIdentity.endpoints &&
                    model == "*" &&
                    upstream == "*" &&
                    url == profile.apiUrl.trimEnd('/') + "/chat/completions"
                ) &&
            policyUrl == terms &&
            policySha256.matches(Regex("[0-9a-f]{64}")) &&
            permitsDocument(policyDocument) &&
            credentialSha256 == OpenRouterIdentity.digest(profile.token.orEmpty())

    private fun validUpstream(value: String): Boolean = value.matches(Regex("[a-z0-9][a-z0-9._/-]{0,127}"))

    private fun validPersonalScope(): Boolean = scope == "openrouter-personal-v1" &&
        personalPolicyAssignment in setOf("direct", "inherited") &&
        listOf(personalWorkspaceId, setupId, modelAvailabilityPolicyId).all { value ->
            value?.matches(Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")) == true
        } &&
        providerKeyHash == credentialSha256
}

internal data class VerifiedPrivateRoute(
    val url: String,
    val kind: ClientType,
    val approval: ProviderApproval? = null,
    val upstream: String? = approval?.upstream?.takeUnless { it == "*" },
    val providerOptions: JsonObject = JsonObject(emptyMap()),
    val model: String? = null,
    val routingRevision: String? = null
) {
    companion object {
        const val VERTEX_TERMS = "https://docs.cloud.google.com/vertex-ai/generative-ai/docs/vertex-ai-zero-data-retention"
        const val GATEWAY_PATH = "/gateway/privacy/inference"

        fun gateway(profile: PlatformV2): String? {
            if (profile.compatibleType !in setOf(ClientType.LLAMA, ClientType.OLLAMA)) return null
            val base = profile.apiUrl.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/v1")
            if (!PrivacyProfileEligibility.privateGateway(base) || java.net.URI(base).rawPath.orEmpty().isNotEmpty()) return null
            return base + GATEWAY_PATH
        }

        fun providerEndpoint(profile: PlatformV2): String? {
            if (profile.batchMode ||
                !profile.batchApiUrl.isNullOrBlank() ||
                (profile.compatibleType != ClientType.OPENROUTER && !profile.openRouterRouting.isNullOrBlank()) ||
                (if (profile.compatibleType == ClientType.OPENROUTER) !PrivateOpenRouterRouting.selectedModel(profile.model) else !profile.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,190}"))) ||
                profile.model.startsWith("openrouter/") ||
                (profile.compatibleType != ClientType.OPENROUTER && (profile.model.endsWith("-latest", true) || profile.model.endsWith(":latest", true))) ||
                profile.model.endsWith(":online") ||
                profile.token.orEmpty().any { it.isWhitespace() || it == ',' || it == ';' }
            ) {
                return null
            }
            val base = profile.apiUrl.trimEnd('/')
            return when (profile.compatibleType) {
                ClientType.OPENROUTER -> if (base in OpenRouterIdentity.endpoints && !profile.token.isNullOrBlank() && profile.model.count { it == '/' } == 1 && PrivateOpenRouterRouting.options(profile.openRouterRouting) != null) "$base/chat/completions" else null
                ClientType.OPENAI -> if (base in setOf("", "https://api.openai.com/v1") && !profile.token.isNullOrBlank()) "https://api.openai.com/v1/chat/completions" else null
                ClientType.GOOGLE -> if (!profile.token.isNullOrBlank() && base.matches(Regex("""https://(?:[a-z0-9-]+-)?aiplatform\.googleapis\.com/v1(?:beta1)?/projects/[a-z0-9-]+/locations/[a-z0-9-]+/endpoints/openapi"""))) "$base/chat/completions" else null
                ClientType.ANTHROPIC -> if (base in setOf("", "https://api.anthropic.com", "https://api.anthropic.com/v1") && !profile.token.isNullOrBlank()) "https://api.anthropic.com/v1/messages" else null
                ClientType.FREE -> if (base == FreeAiProvider.OVHCLOUD.apiUrl && profile.token.isNullOrEmpty()) FreeAiProvider.OVHCLOUD.chatCompletionsUrl else null
                else -> null
            }
        }

        fun strictCatalogMatch(rows: JsonArray, proof: ProviderApproval): Boolean {
            val matches = rows.filterIsInstance<JsonObject>().filter {
                it["model_id"] == JsonPrimitive(proof.model) && it["tag"] == JsonPrimitive(proof.upstream)
            }
            return matches.size == 1 && PrivateOpenRouterRouting.strictRow(matches.single())
        }
    }
}

/** Only bounded, known request options can enter the private text lane. No model fallbacks or plugins. */
internal object PrivateOpenRouterRouting {
    private val slug = Regex("[a-z0-9][a-z0-9._/-]{0,127}")
    private val modelSlug = Regex("~?[A-Za-z0-9][A-Za-z0-9_.-]{0,80}/[A-Za-z0-9][A-Za-z0-9_.:-]{0,120}")

    /** A receipt for one complete intended mutation cannot reconcile an update to another model group. */
    fun guardrailUpdateIdentity(guardrailId: String, keyHash: String, update: JsonObject): String {
        fun canonical(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        val intent = JsonArray(
            listOf(
                JsonPrimitive("openrouter-guardrail-update-v1"),
                JsonPrimitive(guardrailId),
                JsonPrimitive(keyHash),
                canonical(update)
            )
        )
        return OpenRouterIdentity.digest(intent.toString())
    }

    fun selectedModel(value: String): Boolean = modelSlug.matches(value) &&
        !value.removePrefix("~").startsWith("openrouter/") &&
        (':' !in value || value.substringAfter(':') in setOf("free", "latest"))

    private fun concrete(value: String): Boolean = selectedModel(value) &&
        !value.startsWith("~") &&
        !value.endsWith("-latest", true) &&
        !value.endsWith(":latest", true)

    data class ModelTarget(val model: String, val canonical: String)

    fun aliasTarget(metadata: JsonObject, selected: String? = null): String? {
        val data = metadata["data"] as? JsonObject ?: return null
        if (selected != null && data["id"] != JsonPrimitive(selected) && data["canonical_slug"] != JsonPrimitive(selected)) return null
        return (data["alias_target"] as? JsonObject)?.get("slug")?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString && concrete(it.content) }?.content
    }

    fun concreteTarget(metadata: JsonObject, selected: String, allowAlias: Boolean): ModelTarget? {
        val data = metadata["data"] as? JsonObject ?: return null
        if (data["alias_target"] != null && data["alias_target"] != kotlinx.serialization.json.JsonNull) return null
        fun text(key: String) = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val model = text("id")?.takeIf(::concrete) ?: return null
        val canonical = text("canonical_slug")?.takeIf(::concrete) ?: return null
        if (selected != model && (!allowAlias || selected != canonical)) return null
        // Never silently turn a free selection into a paid model or attach an online variant.
        if (selected.endsWith(":free") != model.endsWith(":free")) return null
        if ((data["endpoints"] as? JsonArray)?.contains(JsonPrimitive("/api/v1/chat/completions")) != true) return null
        return ModelTarget(model, canonical)
    }
    private val generation = setOf("stream", "max_tokens", "temperature", "top_p", "top_k", "frequency_penalty", "presence_penalty", "repetition_penalty", "seed")
    private val keys = setOf("order", "only", "ignore", "allow_fallbacks", "require_parameters", "data_collection", "zdr", "quantizations", "sort", "max_price", "preferred_min_throughput", "preferred_max_latency", "enforce_distillable_text")

    fun options(raw: String?): JsonObject? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching JsonObject(emptyMap())
        require(raw.length <= 32768)
        val root = Json.parseToJsonElement(raw) as JsonObject
        val wrapped = "provider" in root || "fallback_models" in root || root.keys.any { it in generation }
        val provider = if (wrapped) {
            require(root.keys.all { it == "provider" || it == "fallback_models" || it in generation })
            require(root["fallback_models"] == null || root["fallback_models"] == kotlinx.serialization.json.JsonNull || root["fallback_models"] == JsonArray(emptyList()))
            when (val value = root["provider"]) {
                null, kotlinx.serialization.json.JsonNull -> JsonObject(emptyMap())
                else -> value as JsonObject
            }
        } else {
            root
        }
        require(provider.keys.all { it in keys })
        for (key in listOf("only", "ignore", "order")) {
            provider[key]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { value ->
                val values = value as JsonArray
                require(values.size <= 100 && values.all { it is JsonPrimitive && it.isString && slug.matches(it.content) })
            }
        }
        for (key in listOf("allow_fallbacks", "require_parameters", "zdr", "enforce_distillable_text")) {
            provider[key]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { require(it == JsonPrimitive(true) || it == JsonPrimitive(false)) }
        }
        provider["data_collection"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { require(it == JsonPrimitive("allow") || it == JsonPrimitive("deny")) }
        provider["sort"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let {
            require(it in listOf("price", "price-asc", "latency", "throughput").map(::JsonPrimitive))
        }
        provider["quantizations"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { values ->
            require(values is JsonArray && values.size <= 100 && values.all { it is JsonPrimitive && it.isString && it.content.matches(Regex("[A-Za-z0-9_-]{1,40}")) })
        }
        fun decimal(value: kotlinx.serialization.json.JsonElement, allowString: Boolean = false): Boolean = value is JsonPrimitive &&
            (!value.isString || allowString) &&
            value.content.length <= 96 &&
            value.content.toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() in -18..24 && it.precision() <= 40 } == true
        provider["max_price"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { prices ->
            require(prices is JsonObject && prices.keys.all { it in setOf("prompt", "completion", "image", "request") } && prices.values.all { decimal(it, allowString = true) })
        }
        for (key in listOf("preferred_min_throughput", "preferred_max_latency")) {
            provider[key]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { value ->
                require(if (value is JsonObject) value.isNotEmpty() && value.keys.all { it in setOf("p50", "p75", "p90", "p99") } && value.values.all { decimal(it) } else decimal(value))
            }
        }
        JsonObject(provider.filterValues { it != kotlinx.serialization.json.JsonNull })
    }.getOrNull()

    /** Profile preferences may narrow the connection policy, but cannot remove its restrictions. */
    fun mergeOptions(defaults: JsonObject, profile: JsonObject): JsonObject? = runCatching {
        val base = requireNotNull(options(defaults.toString()))
        val override = requireNotNull(options(profile.toString()))
        val merged = (base + override).toMutableMap()
        fun values(source: JsonObject, key: String) = (source[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        fun matches(selector: String, endpoint: String) = selector == endpoint || endpoint.startsWith("$selector/")
        fun putValues(key: String, values: List<String>) {
            if (values.isEmpty()) merged.remove(key) else merged[key] = JsonArray(values.distinct().map(::JsonPrimitive))
        }
        val baseOnly = values(base, "only")
        val profileOnly = values(override, "only")
        val only = when {
            baseOnly.isEmpty() -> profileOnly
            profileOnly.isEmpty() -> baseOnly
            else -> baseOnly.flatMap { first ->
                profileOnly.mapNotNull { second ->
                    when {
                        matches(first, second) -> second
                        matches(second, first) -> first
                        else -> null
                    }
                }
            }.distinct().also { require(it.isNotEmpty()) }
        }
        val ignore = (values(base, "ignore") + values(override, "ignore")).distinct()
        require(only.none { allowed -> ignore.any { denied -> matches(denied, allowed) } })
        putValues("only", only)
        putValues("ignore", ignore)
        putValues("order", values(override, "order").ifEmpty { values(base, "order") })
        val baseQuantizations = values(base, "quantizations")
        val profileQuantizations = values(override, "quantizations")
        val quantizations = when {
            baseQuantizations.isEmpty() -> profileQuantizations
            profileQuantizations.isEmpty() -> baseQuantizations
            else -> baseQuantizations.intersect(profileQuantizations.toSet()).toList().also { require(it.isNotEmpty()) }
        }
        putValues("quantizations", quantizations)
        if (base["allow_fallbacks"] == JsonPrimitive(false)) merged["allow_fallbacks"] = JsonPrimitive(false)
        for (key in listOf("require_parameters", "zdr", "enforce_distillable_text")) {
            if (base[key] == JsonPrimitive(true) || override[key] == JsonPrimitive(true)) merged[key] = JsonPrimitive(true)
        }
        if (base["data_collection"] == JsonPrimitive("deny")) merged["data_collection"] = JsonPrimitive("deny")
        val basePrices = base["max_price"] as? JsonObject
        val profilePrices = override["max_price"] as? JsonObject
        if (basePrices != null || profilePrices != null) {
            merged["max_price"] = JsonObject(
                (basePrices.orEmpty().keys + profilePrices.orEmpty().keys).associateWith { key ->
                    listOfNotNull(basePrices?.get(key), profilePrices?.get(key)).minBy { (it as JsonPrimitive).content.toBigDecimal() }
                }
            )
        }
        requireNotNull(options(JsonObject(merged).toString()))
    }.getOrNull()

    fun strictRow(row: JsonObject): Boolean =
        row["safety_retention"] == JsonPrimitive(false) && retentionMetadataAgrees(row)

    /** Inventory fields may be absent, but any that are present must agree with the ZDR catalog. */
    private fun retentionMetadataAgrees(row: JsonObject): Boolean =
        listOf("safety_retention", "requires_safety_retention").all { row[it] == null || row[it] == JsonPrimitive(false) } &&
            listOf("retention_tier", "data_policy_tier").all { row[it] == null || row[it] == JsonPrimitive("zdr") }

    enum class Failure { CATALOG_MALFORMED, MODEL_NOT_LISTED, NO_STRICT_ZDR_ENDPOINT, PROVIDER_RESTRICTIONS, AMBIGUOUS_ENDPOINT }
    data class Selection(val upstream: String? = null, val failure: Failure? = null)

    /** The full model inventory detects prefix selectors that would also admit a less-private variant. */
    fun select(catalog: JsonArray, inventory: JsonObject, model: String, approvedUpstream: String?, options: JsonObject, canonical: String = model): Selection {
        val data = inventory["data"] as? JsonObject ?: return Selection(failure = Failure.CATALOG_MALFORMED)
        if (data["id"] != JsonPrimitive(model) && data["id"] != JsonPrimitive(canonical)) return Selection(failure = Failure.CATALOG_MALFORMED)
        val all = data["endpoints"] as? JsonArray ?: return Selection(failure = Failure.CATALOG_MALFORMED)
        fun tag(row: JsonObject): String? = (row["tag"] as? JsonPrimitive)?.takeIf { it.isString && slug.matches(it.content) }?.content
        if (catalog.any { it !is JsonObject || tag(it) == null || (it["model_id"] as? JsonPrimitive)?.isString != true } ||
            all.any { it !is JsonObject || tag(it) == null || it["model_id"] != JsonPrimitive(canonical) }
        ) {
            return Selection(failure = Failure.CATALOG_MALFORMED)
        }
        val complete = all.filterIsInstance<JsonObject>()
        val matching = catalog.filterIsInstance<JsonObject>().filter { it["model_id"] == JsonPrimitive(canonical) }
        if (complete.isEmpty() || matching.isEmpty()) return Selection(failure = Failure.MODEL_NOT_LISTED)
        val tags = complete.map { requireNotNull(tag(it)) }
        if (tags.size != tags.distinct().size || matching.map { tag(it) }.distinct().size != matching.size) {
            return Selection(failure = Failure.AMBIGUOUS_ENDPOINT)
        }
        val strict = matching.filter(::strictRow).mapNotNull(::tag).toSet()
        val candidates = tags.filter { it in strict && (approvedUpstream == null || it == approvedUpstream) }
        if (candidates.isEmpty()) return Selection(failure = Failure.NO_STRICT_ZDR_ENDPOINT)
        fun values(key: String) = (options[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        fun matches(selector: String, endpoint: String) = endpoint == selector || endpoint.startsWith("$selector/")
        val only = values("only")
        val ignore = values("ignore")
        val order = values("order")
        val restricted = candidates.filter { endpoint ->
            (only.isEmpty() || only.any { matches(it, endpoint) }) &&
                ignore.none { matches(it, endpoint) } &&
                (options["allow_fallbacks"] != JsonPrimitive(false) || order.isEmpty() || order.any { matches(it, endpoint) })
        }
        if (restricted.isEmpty()) return Selection(failure = Failure.PROVIDER_RESTRICTIONS)
        val safe = restricted.filter { candidate ->
            // `only:["provider"]` expands to every provider/variant for the selected model.
            complete.filter { matches(candidate, requireNotNull(tag(it))) }.all { row ->
                val endpoint = requireNotNull(tag(row))
                endpoint in strict &&
                    retentionMetadataAgrees(row) &&
                    ignore.none { denied -> matches(denied, endpoint) } &&
                    (row["service_tier"] == null || row["service_tier"] == kotlinx.serialization.json.JsonNull)
            }
        }
        if (safe.isEmpty()) return Selection(failure = Failure.AMBIGUOUS_ENDPOINT)
        val chosen = safe.sortedWith(compareBy<String> { endpoint -> order.indexOfFirst { matches(it, endpoint) }.takeIf { it >= 0 } ?: Int.MAX_VALUE }.thenBy { it }).first()
        return Selection(upstream = chosen)
    }
}
