package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore implementation for SecretRepository
 */
class SecretDataSourceImpl(
    private val dataStore: DataStore<Preferences>
) : SecretRepository {
    private companion object {
        val OPENROUTER_CREDITS_KEY = stringPreferencesKey("openrouter_credits")
        val OPENROUTER_MANAGEMENT_KEY = stringPreferencesKey("openrouter_management_key")
    }

    override suspend fun getSecret(key: String): String? {
        return dataStore.data.map { preferences ->
            preferences[stringPreferencesKey(key)]
        }.first()
    }

    override suspend fun setSecret(key: String, value: String) {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = value
        }
    }

    override suspend fun deleteSecret(key: String) {
        dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
        }
    }

    override fun getAllSecrets(): Flow<Map<String, String>> {
        return dataStore.data.map { preferences ->
            preferences.filterKeys { key ->
                key is StringPreferencesKey
            }.mapKeys { (key, _) ->
                key as StringPreferencesKey
            }.mapValues { (_, value) ->
                value as String
            }
        }
    }
}
