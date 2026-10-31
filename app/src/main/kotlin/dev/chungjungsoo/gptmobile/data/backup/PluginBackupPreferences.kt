package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tools-only restores preserve plugin choices without replacing unrelated UI/AI settings. */
internal object PluginBackupPreferences {
    private const val KEY = "advanced_feature_settings_json"
    private val fields = setOf("toolPluginStates", "pluginExecution", "remoteMcpConnections")

    fun capture(preferences: Map<String, BackupValue>): Map<String, BackupValue> = preferences.filterKeys { it == KEY }

    fun merge(current: Map<String, BackupValue>, incoming: Map<String, BackupValue>): Map<String, BackupValue> {
        val raw = incoming[KEY]?.value ?: return current
        val restored = Json.parseToJsonElement(sanitizeAcademic(incoming).getValue(KEY).value) as? JsonObject ?: error("Invalid plugin settings.")
        val existing = current[KEY]?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
        val profiles = (existing["profileBehavior"] as? JsonObject).orEmpty().toMutableMap()
        (restored["profileBehavior"] as? JsonObject).orEmpty().forEach { (id, value) ->
            val selected = (value as? JsonObject).orEmpty().filterKeys { it in setOf("toolPluginStates", "researchDepthOverride") }
            profiles[id] = JsonObject((profiles[id] as? JsonObject).orEmpty() + selected)
        }
        val updated = JsonObject(existing + restored.filterKeys { it in fields } + ("profileBehavior" to JsonObject(profiles)))
        return current + (KEY to BackupValue("string", updated.toString()))
    }

    /** Restore nonsecret preferences, but never reopen academic network access or a gateway grant. */
    fun sanitizeAcademic(preferences: Map<String, BackupValue>): Map<String, BackupValue> {
        val raw = preferences[KEY]?.value ?: return preferences
        val original = Json.parseToJsonElement(raw) as? JsonObject ?: return preferences
        // Restored chat/profile/connection IDs are not proof of the original account
        // binding. Re-select a GitHub workspace after restore; never attach its scope
        // to an unrelated room that happens to reuse a numeric ID.
        val saved = JsonObject(original.filterKeys { it !in setOf("conversationGitHub", "githubMainProfiles", "githubPrepareIndex", "gatewayEngineeringJobs") })
        val sanitized = if (saved == original) preferences else preferences + (KEY to BackupValue("string", saved.toString()))
        if ("conversationResearch" !in saved && "deep_research" !in raw) return sanitized
        val states = JsonObject((saved["toolPluginStates"] as? JsonObject).orEmpty() + ("deep_research" to JsonPrimitive(false)))
        val conversations = JsonObject(
            (saved["conversationResearch"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                JsonObject((value as? JsonObject).orEmpty().filterKeys { it != "gatewayConnectionUid" } + mapOf("researchToggle" to JsonPrimitive(false), "allowNativeFallback" to JsonPrimitive(false)))
            }
        )
        val profiles = JsonObject(
            (saved["profileBehavior"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                val profile = (value as? JsonObject).orEmpty()
                JsonObject(profile + ("toolPluginStates" to JsonObject((profile["toolPluginStates"] as? JsonObject).orEmpty() + ("deep_research" to JsonPrimitive(false)))))
            }
        )
        val updated = JsonObject(saved + mapOf("toolPluginStates" to states, "conversationResearch" to conversations, "profileBehavior" to profiles))
        return preferences + (KEY to BackupValue("string", updated.toString()))
    }
}
