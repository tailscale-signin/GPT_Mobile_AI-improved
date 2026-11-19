package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Exact API observations for personal setup; labels and missing fields never establish account identity. */
internal object OpenRouterWorkspacePrivacy {
    private const val MANAGEMENT_ORIGIN = "https://openrouter.ai/api/v1"
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val workspacePattern = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")

    data class KeyBinding(val hash: String, val workspaceId: String)

    data class Settings(
        val workspaceId: String,
        val name: String,
        val promptLogging: Boolean,
        val dataCollection: Boolean,
        val broadcast: Boolean
    ) {
        val strict: Boolean get() = !promptLogging && !dataCollection && !broadcast
    }

    val strictPatch: JsonObject = JsonObject(
        mapOf(
            "is_observability_io_logging_enabled" to JsonPrimitive(false),
            "is_data_discount_logging_enabled" to JsonPrimitive(false),
            "is_observability_broadcast_enabled" to JsonPrimitive(false)
        )
    )

    /** The management lookup must return the hash of the exact inference credential used by the profile. */
    fun parseKey(root: JsonObject, expectedHash: String, nowMillis: Long): KeyBinding? {
        if (!hashPattern.matches(expectedHash)) return null
        val data = root["data"] as? JsonObject ?: return null
        if (data["hash"] != JsonPrimitive(expectedHash) || data["disabled"] != JsonPrimitive(false)) return null
        val workspace = data.string("workspace_id")?.takeIf(workspacePattern::matches) ?: return null
        val expires = data["expires_at"] ?: return null
        if (expires != JsonNull) {
            val expiry = (expires as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 64 }
                ?.content?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            if (!expiry.isAfter(Instant.ofEpochMilli(nowMillis))) return null
        }
        return KeyBinding(expectedHash, workspace)
    }

    /** Reads all three workspace controls without treating absent or malformed values as disabled. */
    fun parseWorkspace(root: JsonObject, expectedWorkspace: String): Settings? {
        if (!workspacePattern.matches(expectedWorkspace)) return null
        val data = root["data"] as? JsonObject ?: return null
        if (data["id"] != JsonPrimitive(expectedWorkspace)) return null
        val name = data.string("name")?.takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) } ?: return null
        return Settings(
            workspaceId = expectedWorkspace,
            name = name,
            promptLogging = data.literalBoolean("is_observability_io_logging_enabled") ?: return null,
            dataCollection = data.literalBoolean("is_data_discount_logging_enabled") ?: return null,
            broadcast = data.literalBoolean("is_observability_broadcast_enabled") ?: return null
        )
    }

    /** A durable receipt can reconcile only the same origin, inference key, workspace and exact update. */
    fun mutationIdentity(binding: KeyBinding, patch: JsonObject): String {
        require(hashPattern.matches(binding.hash) && workspacePattern.matches(binding.workspaceId))
        val intent = JsonArray(
            listOf(
                JsonPrimitive("openrouter-workspace-privacy-update-v1"),
                JsonPrimitive(MANAGEMENT_ORIGIN),
                JsonPrimitive(binding.hash),
                JsonPrimitive(binding.workspaceId),
                canonical(patch)
            )
        )
        return OpenRouterIdentity.digest(intent.toString())
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.literalBoolean(name: String): Boolean? = when (get(name)) {
        JsonPrimitive(true) -> true
        JsonPrimitive(false) -> false
        else -> null
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
