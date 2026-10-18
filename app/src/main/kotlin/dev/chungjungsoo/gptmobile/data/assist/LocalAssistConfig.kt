package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Canonical explicit intent; permissions remain in the existing stable-UID grant map. */
data class LocalAssistConfig(val enabled: Boolean, val helperUid: String?, val foregroundPreparation: Boolean)

object LocalAssistSettingsMigration {
    private val json = Json { ignoreUnknownKeys = true }
    fun normalize(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = (root["assistConfigVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        require(version <= 1) { "This Local Assist configuration needs a newer app. The original settings are preserved." }
        // Preserve explicit intent, selected identity, preload-denied intent and every grant verbatim.
        return JsonObject(root.filterKeys { it != "executionPolicy" } + ("assistConfigVersion" to JsonPrimitive(1))).toString()
    }
}

fun dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.localAssistConfig() = LocalAssistConfig(localAssist, localAssistPolicy.profileUid, localAssistPolicy.preloadInForeground)
