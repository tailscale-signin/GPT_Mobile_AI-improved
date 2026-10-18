package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first

internal class CompleteBackupPreferences(private val context: Context, private val dataStore: DataStore<Preferences>) {
    suspend fun read() = dataStore.data.first().asMap().map { (key, value) -> key.name to encode(value) }.toMap()
    fun readShared() = sharedNames().associateWith { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) -> encode(requireNotNull(value)) }
    }

    suspend fun captureConversationSettings(chatIds: Set<Int>): ConversationCombinedPortableSnapshot {
        var result: ConversationCombinedPortableSnapshot? = null
        dataStore.edit { prefs ->
            val key = stringPreferencesKey(ConversationCombinedPortable.FEATURE_KEY)
            val raw = ConversationCombinedPortable.ensureIdentities(prefs[key], chatIds)
            val decoded = dev.chungjungsoo.gptmobile.data.model.FeatureSettingsJson.decode(raw)
            check(decoded.combinedSettingsJournal.values.none { !it.roomSettled }) { "Finish or recover the conversation settings save before backing up." }
            prefs[key] = raw
            result = ConversationCombinedPortable.capture(raw, chatIds)
        }
        return checkNotNull(result)
    }

    suspend fun normalizeAssist() {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey("advanced_feature_settings_json")
            prefs[key]?.let { raw ->
                val next = dev.chungjungsoo.gptmobile.data.model.FeatureSettingsJson.normalize(raw)
                if (next != raw) {
                    prefs[stringPreferencesKey("advanced_feature_settings_before_assist_v1")] = raw
                    prefs[key] = next
                }
            }
        }
    }

    suspend fun replace(values: Map<String, BackupValue>, shared: Map<String, Map<String, BackupValue>>) {
        validate(values, shared)
        val normalized = values.mapValues { (key, value) ->
            if (key == "advanced_feature_settings_json") value.copy(value = dev.chungjungsoo.gptmobile.data.model.FeatureSettingsJson.normalize(value.value)) else value
        }
        dataStore.edit { prefs ->
            prefs.clear()
            normalized.forEach { (name, value) ->
                when (val decoded = decode(value)) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = decoded
                    is Int -> prefs[intPreferencesKey(name)] = decoded
                    is Long -> prefs[longPreferencesKey(name)] = decoded
                    is Float -> prefs[floatPreferencesKey(name)] = decoded
                    is Double -> prefs[doublePreferencesKey(name)] = decoded
                    is String -> prefs[stringPreferencesKey(name)] = decoded
                    is ByteArray -> prefs[byteArrayPreferencesKey(name)] = decoded
                    is Set<*> -> prefs[stringSetPreferencesKey(name)] = decoded.filterIsInstance<String>().toSet()
                }
            }
        }
        (sharedNames() + shared.keys).filterNot { it in SECTION_OWNED_SHARED_PREFERENCES }.forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            shared[name].orEmpty().forEach { (key, value) ->
                when (val decoded = decode(value)) {
                    is Boolean -> editor.putBoolean(key, decoded)
                    is Int -> editor.putInt(key, decoded)
                    is Long -> editor.putLong(key, decoded)
                    is Float -> editor.putFloat(key, decoded)
                    is String -> editor.putString(key, decoded)
                    is Set<*> -> editor.putStringSet(key, decoded.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Could not save restored settings." }
        }
    }

    fun validate(values: Map<String, BackupValue>, shared: Map<String, Map<String, BackupValue>>) {
        values.values.forEach(::decode)
        shared.forEach { (name, entries) ->
            require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid preferences name." }
            entries.values.forEach {
                require(it.type !in setOf("double", "bytes"))
                decode(it)
            }
        }
    }

    private fun sharedNames(): Set<String> = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.filterNot { it in SECTION_OWNED_SHARED_PREFERENCES }.toSet() + "llama_settings"

    private fun encode(value: Any): BackupValue = when (value) {
        is Boolean -> BackupValue("boolean", value.toString())
        is Int -> BackupValue("int", value.toString())
        is Long -> BackupValue("long", value.toString())
        is Float -> BackupValue("float", value.toString())
        is Double -> BackupValue("double", value.toString())
        is String -> BackupValue("string", value)
        is ByteArray -> BackupValue("bytes", Base64.getEncoder().encodeToString(value))
        is Set<*> -> {
            require(value.all { it is String })
            BackupValue("strings", values = value.filterIsInstance<String>().toSet())
        }
        else -> error("Unsupported preference type.")
    }

    private fun decode(value: BackupValue): Any = when (value.type) {
        "boolean" -> value.value.toBooleanStrict()
        "int" -> value.value.toInt()
        "long" -> value.value.toLong()
        "float" -> value.value.toFloat()
        "double" -> value.value.toDouble()
        "string" -> value.value
        "bytes" -> Base64.getDecoder().decode(value.value)
        "strings" -> value.values
        else -> error("Unsupported backup preference type.")
    }
}

// History and qualification mirrors belong to Statistics, never generic Settings.
internal val SECTION_OWNED_SHARED_PREFERENCES = setOf("complete_backup_ui_v1", "profile_benchmarks_v1", "generation_completion_inbox_v1", "conversation_read_state_v1")
