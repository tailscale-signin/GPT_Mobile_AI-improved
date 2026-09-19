package dev.chungjungsoo.gptmobile.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.domain.model.BackupData
import dev.chungjungsoo.gptmobile.domain.model.BackupOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SettingsRepository extensions for favourites and advanced settings persistence.
 */
@Singleton
class SettingsBackupRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // Favourites methods
    suspend fun getFavourites(): List<String> =
        dataStore.data.map { it[FAVOURITES_KEY] ?: emptySet() }.first().toList()

    suspend fun setFavourites(favourites: List<String>) {
        dataStore.edit { it[FAVOURITES_KEY] = favourites.toSet() }
    }

    // Advanced settings methods
    suspend fun getAdvancedSettings(): Map<String, Any?> =
        dataStore.data.map { prefs ->
            prefs.asMap().mapKeys { it.key.name }.mapValues { (_, value) ->
                when (value) {
                    is String -> value
                    is Int -> value
                    is Boolean -> value
                    else -> null
                }
            }
        }.first()

    suspend fun setAdvancedSettings(settings: Map<String, Any?>) {
        dataStore.edit { preferences ->
            settings.forEach { (key, value) ->
                when (value) {
                    is String -> preferences[stringPreferencesKey(key)] = value
                    is Int -> preferences[intPreferencesKey(key)] = value
                    is Boolean -> preferences[booleanPreferencesKey(key)] = value
                }
            }
        }
    }

    // General settings methods
    suspend fun getGeneralSettings(): Map<String, String> =
        dataStore.data.map { prefs ->
            prefs.asMap().mapKeys { it.key.name }.mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap()
        }.first()

    suspend fun setGeneralSettings(settings: Map<String, String>) {
        dataStore.edit { preferences ->
            settings.forEach { (key, value) ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    companion object {
        private val FAVOURITES_KEY = stringSetPreferencesKey("favourites")
    }
}

/**
 * BackupManager implementation offering exactly two options: Create Backup & Restore Backup.
 */
@Singleton
class BackupManager @Inject constructor(
    private val database: ChatDatabaseV2,
    private val settingsRepository: SettingsBackupRepository
) {
    // Exactly two backup options
    fun getBackupOptions(): List<BackupOption> = listOf(
        BackupOption(
            title = "Create Backup",
            description = "Backup all data including conversations, favourites, and advanced settings"
        ),
        BackupOption(
            title = "Restore Backup",
            description = "Restore from a previously created backup file"
        )
    )

    suspend fun createBackup(): Result<BackupData> = withContext(Dispatchers.IO) {
        try {
            val conversations = database.chatRoomDao().getChatRooms()
            val favourites = settingsRepository.getFavourites()
            val advancedSettings = settingsRepository.getAdvancedSettings()
            val generalSettings = settingsRepository.getGeneralSettings()

            Result.success(
                BackupData(
                    conversations = conversations,
                    favourites = favourites,
                    advancedSettings = advancedSettings,
                    generalSettings = generalSettings
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreBackup(backupData: BackupData): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existingChats = database.chatRoomDao().getChatRooms()
            if (existingChats.isNotEmpty()) {
                database.chatRoomDao().deleteChatRooms(*existingChats.toTypedArray())
            }
            backupData.conversations.forEach {
                database.chatRoomDao().addChatRoom(it)
            }
            settingsRepository.setFavourites(backupData.favourites)
            settingsRepository.setAdvancedSettings(backupData.advancedSettings)
            settingsRepository.setGeneralSettings(backupData.generalSettings)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
