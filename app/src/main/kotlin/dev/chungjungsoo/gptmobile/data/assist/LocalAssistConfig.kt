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
        val policy = (root["localAssistPolicy"] as? JsonObject) ?: JsonObject(emptyMap())
        val policyVersion = (policy["version"] as? JsonPrimitive)?.intOrNull ?: 0
        require(policyVersion <= 4) { "This Assist policy needs a newer app. The original settings are preserved." }
        // Preserve explicit intent, manual context, selected UID, preload denial and grants.
        // Pre-v4 data cannot authorize newly introduced image/network/disclosure scopes.
        val scopes = if (policyVersion < 4) {
            mapOf(
                "imageProcessing" to JsonPrimitive(false),
                "autonomousReads" to JsonPrimitive(false),
                "sharePrivateQueries" to JsonPrimitive(false),
                "shareDerivedEvidence" to JsonPrimitive(false)
            )
        } else {
            emptyMap()
        }
        val migrated = JsonObject(policy + scopes + ("version" to JsonPrimitive(4)))
        return JsonObject(root.filterKeys { it != "executionPolicy" } + mapOf("assistConfigVersion" to JsonPrimitive(1), "localAssistPolicy" to migrated)).toString()
    }
}

fun dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.localAssistConfig() = LocalAssistConfig(localAssist, localAssistPolicy.profileUid, localAssistPolicy.preloadInForeground)

/** Revocation and regrant cannot revive artifacts prepared under an earlier consent epoch. */
fun dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.advanceAssistEpoch(previous: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings): dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings =
    if (localAssist != previous.localAssist || localAssistPolicy.copy(permissionEpoch = "") != previous.localAssistPolicy.copy(permissionEpoch = "")) {
        copy(localAssistPolicy = localAssistPolicy.copy(permissionEpoch = java.util.UUID.randomUUID().toString()))
    } else {
        copy(localAssistPolicy = localAssistPolicy.copy(permissionEpoch = previous.localAssistPolicy.permissionEpoch))
    }
