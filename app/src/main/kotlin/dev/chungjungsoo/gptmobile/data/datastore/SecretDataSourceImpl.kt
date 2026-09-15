package dev.chungjungsoo.gptmobile.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

class SecretDataSourceImpl(
    private val context: Context
) : SecretRepository {

    private val secretKey = stringPreferencesKey("secret")

    override suspend fun getSecret(key: String): String? {
        return context.dataStore.data.map { preferences ->
            preferences[secretKey]
        }.first()
    }

    override suspend fun setSecret(key: String, value: String) {
        context.dataStore.edit { preferences ->
            preferences[secretKey] = value
        }
    }

    override suspend fun deleteSecret(key: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(secretKey)
        }
    }

    override fun observeSecret(key: String): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[secretKey]
        }
    }
}
