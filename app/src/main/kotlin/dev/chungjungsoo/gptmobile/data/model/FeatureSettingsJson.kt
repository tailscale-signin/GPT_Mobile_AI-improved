package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.assist.LocalAssistSettingsMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Patch only changed known fields; unrelated future fields survive every writer. */
object FeatureSettingsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun normalize(raw: String): String {
        val root = json.parseToJsonElement(LocalAssistSettingsMigration.normalize(raw)) as? JsonObject
            ?: error("Feature settings must be a JSON object")
        val normalized = root.toMutableMap()
        (root["conversationCombined"] as? JsonObject)?.let { combined ->
            normalized["conversationCombined"] = JsonObject(
                combined.mapValues { (_, entry) ->
                    json.encodeToJsonElement(ConversationCombinedSettingsSerializer, json.decodeFromJsonElement(ConversationCombinedSettingsSerializer, entry))
                }
            )
        }
        (root["settingsHome"] as? JsonObject)?.let { home ->
            val safe = home.toMutableMap()
            for (key in listOf("selectedItems", "selectedProfileUids")) {
                if (key !in safe) continue
                val entries = safe[key] as? JsonArray
                if (entries == null) safe.remove(key) else safe[key] = JsonArray(entries.filter { it is JsonPrimitive && it.isString })
            }
            if ("selectedToolSubjects" in safe) {
                val entries = safe["selectedToolSubjects"] as? JsonArray
                if (entries == null) {
                    safe.remove("selectedToolSubjects")
                } else {
                    safe["selectedToolSubjects"] = JsonArray(
                        entries.filter {
                            runCatching { json.decodeFromJsonElement(HomeToolSubject.serializer(), it) }.isSuccess
                        }
                    )
                }
            }
            if ("schemaVersion" in safe && (safe["schemaVersion"] as? JsonPrimitive)?.intOrNull == null) safe.remove("schemaVersion")
            val sanitized = JsonObject(safe)
            val current = json.decodeFromJsonElement(SettingsHomeConfiguration.serializer(), sanitized)
            normalized["settingsHome"] = mergeElements(
                sanitized,
                json.encodeToJsonElement(SettingsHomeConfiguration.serializer(), current),
                json.encodeToJsonElement(SettingsHomeConfiguration.serializer(), current.normalized())
            )
        }
        return JsonObject(normalized).toString()
    }

    fun decode(raw: String): AppFeatureSettings = json.decodeFromString<AppFeatureSettings>(normalize(raw))

    fun merge(raw: String?, previous: AppFeatureSettings, next: AppFeatureSettings): String {
        val before = json.encodeToJsonElement(AppFeatureSettings.serializer(), previous)
        val after = json.encodeToJsonElement(AppFeatureSettings.serializer(), next)
        val original = raw?.let { json.parseToJsonElement(normalize(it)) } ?: before
        return mergeElements(original, before, after).toString()
    }

    internal fun mergeElements(original: JsonElement, before: JsonElement, after: JsonElement): JsonElement {
        if (before == after) return original
        if (before is JsonArray && after is JsonArray && original is JsonArray) {
            if (original == before) return after
            val identityKey = listOf("id", "uid", "key", "profileUid", "modelId").firstOrNull { key ->
                listOf(before, after).all { entries ->
                    val identities = entries.mapNotNull { (it as? JsonObject)?.get(key) }
                    identities.size == entries.size && identities.distinct().size == entries.size
                }
            }
            val matched = mutableSetOf<Int>()
            return JsonArray(
                after.map { next ->
                    val candidates = before.indices.filter { index ->
                        index !in matched && if (identityKey == null) before[index] == next else (before[index] as? JsonObject)?.get(identityKey) == (next as JsonObject)[identityKey]
                    }
                    if (candidates.isEmpty()) {
                        check(identityKey != null || before.all { it !is JsonObject } || original == before) { "This list contains newer settings that cannot be safely edited by this app." }
                        next
                    } else {
                        val index = candidates.first()
                        matched += index
                        mergeElements(original[index], before[index], next)
                    }
                }
            )
        }
        if (before !is JsonObject || after !is JsonObject || original !is JsonObject) return after
        val result = original.toMutableMap()
        (before.keys - after.keys).forEach(result::remove)
        after.forEach { (key, next) ->
            val prior = before[key]
            result[key] = if (prior != null && original[key] != null) mergeElements(original.getValue(key), prior, next) else next
        }
        return JsonObject(result)
    }
}
