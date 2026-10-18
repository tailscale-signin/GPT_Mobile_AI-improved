package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettingsSerializer
import dev.chungjungsoo.gptmobile.data.model.FeatureSettingsJson
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class ConversationCombinedPortableSnapshot(
    val schemaVersion: Int = 1,
    val conversations: List<PortableConversationCombinedSettings> = emptyList()
)

@Serializable
internal data class PortableConversationCombinedSettings(
    val identity: String,
    val sourceChatId: Int,
    val settings: ConversationCombinedSettings
)

/** Stable identity, never coincidental integer equality, owns a settings-only import. */
internal object ConversationCombinedPortable {
    const val FEATURE_KEY = "advanced_feature_settings_json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val ownedKeys = setOf("conversationCombined", "conversationIdentities", "combinedSettingsJournal", "combinedSettingsDeletions", "deferredConversationCombined")

    fun ensureIdentities(raw: String?, chatIds: Set<Int>): String {
        val root = root(raw)
        val identities = objectField(root, "conversationIdentities").toMutableMap()
        chatIds.filter { it > 0 }.forEach { id ->
            if ((identities[id.toString()] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) identities[id.toString()] = JsonPrimitive(UUID.randomUUID().toString())
        }
        return JsonObject(root + ("conversationIdentities" to JsonObject(identities))).toString()
    }

    fun capture(raw: String, chatIds: Set<Int>): ConversationCombinedPortableSnapshot {
        val root = root(raw)
        val identities = objectField(root, "conversationIdentities")
        val policies = objectField(root, "conversationCombined")
        val entries = chatIds.sorted().map { id ->
            require(id > 0)
            val identity = (identities[id.toString()] as? JsonPrimitive)?.contentOrNull
            require(!identity.isNullOrBlank()) { "Conversation identity is missing." }
            PortableConversationCombinedSettings(identity, id, policies[id.toString()]?.let { json.decodeFromJsonElement(ConversationCombinedSettingsSerializer, it) } ?: ConversationCombinedSettings())
        }
        return ConversationCombinedPortableSnapshot(conversations = entries).also(::validate)
    }

    fun validate(snapshot: ConversationCombinedPortableSnapshot) {
        require(snapshot.schemaVersion == 1) { "Conversation settings require a newer app version." }
        require(snapshot.conversations.all { it.identity.isNotBlank() && it.sourceChatId > 0 }) { "Invalid conversation settings identity." }
        require(snapshot.conversations.map { it.identity }.distinct().size == snapshot.conversations.size) { "Duplicate conversation settings identity." }
        require(snapshot.conversations.map { it.sourceChatId }.distinct().size == snapshot.conversations.size) { "Duplicate conversation settings ID." }
    }

    fun restore(
        currentRaw: String?,
        incomingRaw: String?,
        snapshot: ConversationCombinedPortableSnapshot?,
        restoredIdMap: Map<Int, Int>?,
        existingChatIds: Set<Int>,
        replaceGlobalSettings: Boolean,
        baseRaw: String? = null
    ): String {
        snapshot?.let(::validate)
        val current = root(currentRaw)
        val incoming = root(incomingRaw)
        val base = baseRaw?.let(::root) ?: if (replaceGlobalSettings) incoming else current
        val identities = if (restoredIdMap != null) mutableMapOf() else objectField(current, "conversationIdentities").toMutableMap()
        val policies = if (restoredIdMap != null) mutableMapOf() else objectField(current, "conversationCombined").toMutableMap()
        val deferred = (objectField(current, "deferredConversationCombined") + objectField(incoming, "deferredConversationCombined")).toMutableMap()
        val entries = snapshot?.conversations ?: legacyEntries(incoming, incomingRaw.orEmpty())
        if (restoredIdMap != null) {
            require(restoredIdMap.keys.all { it > 0 } && restoredIdMap.values.all { it > 0 } && restoredIdMap.values.toSet().size == restoredIdMap.size)
            val byId = entries.associateBy { it.sourceChatId }
            restoredIdMap.forEach { (sourceId, targetId) ->
                val record = byId[sourceId]
                val identity = record?.identity ?: UUID.randomUUID().toString()
                identities[targetId.toString()] = JsonPrimitive(identity)
                val policy = record?.settings ?: ConversationCombinedSettings()
                policies[targetId.toString()] = deferred.remove(identity) ?: json.encodeToJsonElement(ConversationCombinedSettingsSerializer, policy)
            }
            entries.filter { it.sourceChatId !in restoredIdMap }.forEach { deferred[it.identity] = json.encodeToJsonElement(ConversationCombinedSettingsSerializer, it.settings) }
        } else {
            entries.forEach { record ->
                val matching = identities.filter { (id, value) -> id.toIntOrNull() in existingChatIds && (value as? JsonPrimitive)?.contentOrNull == record.identity }.keys.singleOrNull()
                val encoded = json.encodeToJsonElement(ConversationCombinedSettingsSerializer, record.settings)
                if (matching != null) {
                    val previous = policies[matching]?.let { json.decodeFromJsonElement(ConversationCombinedSettingsSerializer, it) }
                    val incomingSettings = record.settings
                    val adopted = if (previous != null && previous.isSupported && incomingSettings.isSupported && previous.copy(revision = 0) != incomingSettings.copy(revision = 0)) {
                        incomingSettings.copy(revision = maxOf(incomingSettings.revision, Math.addExact(previous.revision, 1)))
                    } else if (previous != null && previous.isSupported && incomingSettings.isSupported) {
                        incomingSettings.copy(revision = maxOf(previous.revision, incomingSettings.revision))
                    } else {
                        incomingSettings
                    }
                    policies[matching] = json.encodeToJsonElement(ConversationCombinedSettingsSerializer, adopted)
                    deferred.remove(record.identity)
                } else {
                    deferred[record.identity] = encoded
                }
            }
        }
        return JsonObject(
            base.filterKeys { it !in ownedKeys } + mapOf(
                "conversationCombined" to JsonObject(policies),
                "conversationIdentities" to JsonObject(identities),
                "deferredConversationCombined" to JsonObject(deferred),
                "combinedSettingsJournal" to if (restoredIdMap == null && !replaceGlobalSettings) objectField(current, "combinedSettingsJournal") else JsonObject(emptyMap()),
                "combinedSettingsDeletions" to if (restoredIdMap == null) objectField(current, "combinedSettingsDeletions") else JsonObject(emptyMap())
            )
        ).toString()
    }

    private fun legacyEntries(incoming: JsonObject, raw: String): List<PortableConversationCombinedSettings> {
        val identities = objectField(incoming, "conversationIdentities")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return objectField(incoming, "conversationCombined").mapNotNull { (key, payload) ->
            val id = key.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val identity = (identities[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: "legacy:$digest:$id"
            PortableConversationCombinedSettings(identity, id, json.decodeFromJsonElement(ConversationCombinedSettingsSerializer, payload))
        }
    }

    private fun root(raw: String?): JsonObject = json.parseToJsonElement(FeatureSettingsJson.normalize(raw ?: "{}")) as JsonObject
    private fun objectField(root: JsonObject, name: String): JsonObject = root[name] as? JsonObject ?: JsonObject(emptyMap())
}
