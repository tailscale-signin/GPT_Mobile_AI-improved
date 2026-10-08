package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve unrelated/unknown settings while requiring new explicit Amazon grants after restore. */
internal object AmazonRestorePolicy {
    fun disableGrants(preferences: Map<String, BackupValue>): Map<String, BackupValue> {
        val key = "advanced_feature_settings_json"
        val raw = preferences[key]?.value
        val features = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        fun disabled(value: JsonObject): JsonObject {
            val states = (value["toolPluginStates"] as? JsonObject).orEmpty()
            return JsonObject(value + ("toolPluginStates" to JsonObject(states + (ToolPluginId.AMAZON_FREE to JsonPrimitive(false)))))
        }
        val profiles = (features["profileBehavior"] as? JsonObject).orEmpty().mapValues { (_, value) -> disabled(value as? JsonObject ?: JsonObject(emptyMap())) }
        val safe = JsonObject(disabled(features) + ("profileBehavior" to JsonObject(profiles)))
        return preferences + (key to BackupValue("string", safe.toString()))
    }
}
