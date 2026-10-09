package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Tools-only restores preserve plugin choices without replacing unrelated UI/AI settings. */
internal object PluginBackupPreferences {
    private const val KEY = "advanced_feature_settings_json"
    private val fields = setOf("toolPluginStates", "pluginExecution", "remoteMcpConnections")

    fun capture(preferences: Map<String, BackupValue>): Map<String, BackupValue> = preferences.filterKeys { it == KEY }

    fun merge(current: Map<String, BackupValue>, incoming: Map<String, BackupValue>): Map<String, BackupValue> {
        val raw = incoming[KEY]?.value ?: return current
        val restored = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid plugin settings.")
        val existing = current[KEY]?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
        val profiles = (existing["profileBehavior"] as? JsonObject).orEmpty().toMutableMap()
        (restored["profileBehavior"] as? JsonObject).orEmpty().forEach { (id, value) ->
            val states = (value as? JsonObject)?.get("toolPluginStates") ?: return@forEach
            profiles[id] = JsonObject((profiles[id] as? JsonObject).orEmpty() + ("toolPluginStates" to states))
        }
        val updated = JsonObject(existing + restored.filterKeys { it in fields } + ("profileBehavior" to JsonObject(profiles)))
        return current + (KEY to BackupValue("string", updated.toString()))
    }
}
