package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Aggregate policy evaluation only; provider names, model names and credentials stay out of reports. */
internal data class PrivacyEndpointObservation(
    val catalogEntries: Int,
    val modelEndpoints: Int,
    val compatibleEndpoints: Int,
    val allowlistExcluded: Int,
    val denylistExcluded: Int,
    val fallbackExcluded: Int
) {
    val compatible: Boolean get() = compatibleEndpoints > 0

    fun fields(metadataAgeMillis: Long): JsonObject = buildJsonObject {
        put("privacy_catalog_entries", catalogEntries)
        put("privacy_model_endpoint_count", modelEndpoints)
        put("privacy_compatible_endpoint_count", compatibleEndpoints)
        put("privacy_allowlist_excluded_count", allowlistExcluded)
        put("privacy_denylist_excluded_count", denylistExcluded)
        put("privacy_fallback_excluded_count", fallbackExcluded)
        put("privacy_metadata_age_ms", metadataAgeMillis.coerceAtLeast(0))
        put(
            "privacy_blockers",
            JsonArray(
                buildList {
                    if (modelEndpoints == 0) add("MODEL_NOT_IN_ZDR_CATALOG")
                    if (allowlistExcluded > 0) add("PROVIDER_ALLOWLIST")
                    if (denylistExcluded > 0) add("PROVIDER_DENYLIST")
                    if (fallbackExcluded > 0) add("FALLBACK_ORDER")
                }.map(::JsonPrimitive)
            )
        )
    }

    companion object {
        fun evaluate(rows: JsonArray, model: String, policy: OpenRouterProviderRouting): PrivacyEndpointObservation? {
            if (model.startsWith("~") || model in setOf("openrouter/auto", "openrouter/free")) return null
            if (rows.any { row ->
                    row !is JsonObject ||
                        listOf("model_id", "tag").any { key ->
                            val value = row[key] as? JsonPrimitive
                            value?.isString != true || value.content.isBlank()
                        }
                }
            ) {
                return null
            }
            fun matches(choice: String, tag: String) = tag.equals(choice, true) || tag.startsWith("$choice/", true)
            val matching = rows.filterIsInstance<JsonObject>().filter { (it["model_id"] as JsonPrimitive).content == model }
            var eligible = 0
            var allowlist = 0
            var denylist = 0
            var fallback = 0
            for (row in matching) {
                val tag = (row["tag"] as JsonPrimitive).content
                when {
                    !policy.only.isNullOrEmpty() && policy.only.none { matches(it, tag) } -> allowlist++
                    policy.ignore.orEmpty().any { matches(it, tag) } -> denylist++
                    policy.allowFallbacks == false && !policy.order.isNullOrEmpty() && policy.order.none { matches(it, tag) } -> fallback++
                    else -> eligible++
                }
            }
            return PrivacyEndpointObservation(rows.size, matching.size, eligible, allowlist, denylist, fallback)
        }
    }
}
