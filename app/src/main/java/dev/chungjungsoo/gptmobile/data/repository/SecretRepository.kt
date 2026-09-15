package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.chungjungsoo.gptmobile.data.backup.EncryptedBackupManager
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SecretRepository @Inject constructor(
    private val context: Context,
    private val encryptedBackupManager: EncryptedBackupManager
) {
    private val dataStore = context.dataStore

    suspend fun getSecret(key: String): String? {
        return try {
            dataStore.data.map { preferences ->
                preferences[key]
            }.first()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setSecret(key: String, value: String) {
        try {
            dataStore.data.update { preferences ->
                preferences + key to value
            }
        } catch (e: Exception) {
            // Handle error silently
        }
    }

    suspend fun deleteSecret(key: String) {
        try {
            dataStore.data.update { preferences ->
                preferences - key
            }
        } catch (e: Exception) {
            // Handle error silently
        }
    }

    suspend fun getAllSecrets(): Map<String, String> {
        return try {
            dataStore.data.first()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun observeSecret(key: String): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[key]
        }
    }
}