package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ChatActionPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ChatActionPreferencesRepository {

    private val dynamicActionsEnabledKey = booleanPreferencesKey("dynamic_actions_enabled")
    private val actionPromptHistoryKey = stringPreferencesKey("action_prompt_history_json")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isDynamicActionsEnabled(): Boolean = dataStore.data.map { pref ->
        pref[dynamicActionsEnabledKey] ?: true
    }.first()

    override suspend fun setDynamicActionsEnabled(enabled: Boolean) {
        dataStore.edit { pref ->
            pref[dynamicActionsEnabledKey] = enabled
        }
    }

    override fun observeDynamicActionsEnabled(): Flow<Boolean> = dataStore.data.map { pref ->
        pref[dynamicActionsEnabledKey] ?: true
    }

    override suspend fun getActionPromptHistory(): List<String> = dataStore.data.map { pref ->
        val raw = pref[actionPromptHistoryKey]
        if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }.first()

    override suspend fun recordActionPrompt(prompt: String) {
        if (prompt.isBlank()) return
        val current = getActionPromptHistory().toMutableList()
        current.remove(prompt)
        current.add(0, prompt)
        val capped = current.take(30)
        dataStore.edit { pref ->
            pref[actionPromptHistoryKey] = json.encodeToString(capped)
        }
    }

    override suspend fun clearActionPromptHistory() {
        dataStore.edit { pref ->
            pref.remove(actionPromptHistoryKey)
        }
    }

    override fun observeActionPromptHistory(): Flow<List<String>> = dataStore.data.map { pref ->
        val raw = pref[actionPromptHistoryKey]
        if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
}
