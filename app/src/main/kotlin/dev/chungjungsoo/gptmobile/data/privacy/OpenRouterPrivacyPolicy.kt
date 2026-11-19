package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Modern model-availability evidence. Existing policies are observed without replacing their restrictions. */
internal object OpenRouterPrivacyPolicy {
    private val uuid = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    val strictFields = JsonObject(
        listOf("anthropic", "openai", "google", "xai", "other").associate { "enforce_zdr_$it" to JsonPrimitive(true) } +
            mapOf("allow_safety_retention_google" to JsonPrimitive(false), "allow_safety_retention_other" to JsonPrimitive(false))
    )

    data class Assignment(val raw: JsonObject, val optedOut: Boolean, val policies: List<String>, val defaults: List<String>) {
        val selected: String? get() = if (optedOut) null else policies.singleOrNull() ?: defaults.singleOrNull()
    }

    fun assignment(root: JsonObject): Assignment? {
        val data = root["data"] as? JsonObject ?: return null
        val optedOut = when (data["opted_out"]) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> return null
        }
        fun ids(name: String): List<String>? {
            val values = data[name] as? JsonArray ?: return null
            if (values.size > 1) return null
            return values.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString && uuid.matches(value.content) }?.content ?: return null }
        }
        val policies = ids("policy_ids") ?: return null
        val defaults = ids("workspace_default_policy_ids") ?: return null
        if (optedOut && policies.isNotEmpty()) return null
        val snapshot = JsonObject(data.filterKeys { it in setOf("opted_out", "policy_ids", "workspace_default_policy_ids") })
        return Assignment(snapshot, optedOut, policies, defaults)
    }

    fun policy(root: JsonObject, id: String? = null): JsonObject? {
        val data = root["data"] as? JsonObject ?: return null
        val observedId = (data["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!uuid.matches(observedId) || id != null && observedId != id || data["policy_type"] != JsonPrimitive("model_availability")) return null
        val config = data["config"] as? JsonObject ?: return null
        if (config.toString().length > 16384) return null
        return data
    }

    fun strict(config: JsonObject): Boolean = strictFields.all { (name, value) -> config[name] == value }

    /** A direct observed assignment avoids guessing inherited/member/default policy applicability. */
    fun directlyProtects(data: JsonObject, keyHash: String): Boolean {
        if (!strict(data["config"] as? JsonObject ?: return false)) return false
        fun hashes(name: String): List<String>? {
            val values = data[name] as? JsonArray ?: return null
            if (values.size > 10000) return null
            return values.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content.matches(Regex("[0-9a-f]{64}")) }?.content ?: return null }
        }
        return keyHash in (hashes("api_key_hashes") ?: return false) &&
            keyHash !in (hashes("opted_out_api_key_hashes") ?: return false) &&
            keyHash !in (hashes("overridden_api_key_hashes") ?: return false)
    }

    fun assignmentUpdate(workspaceId: String, policyId: String, expected: Assignment) = JsonObject(
        mapOf(
            "workspace_id" to JsonPrimitive(workspaceId),
            "policy_type" to JsonPrimitive("model_availability"),
            "policy_id" to JsonPrimitive(policyId),
            "assigned" to JsonPrimitive(true),
            "expected_assignment" to expected.raw
        )
    )

    fun nullableText(data: JsonObject, name: String): String? = (data[name] as? JsonPrimitive)?.takeIf { it != JsonNull && it.isString }?.content
}
