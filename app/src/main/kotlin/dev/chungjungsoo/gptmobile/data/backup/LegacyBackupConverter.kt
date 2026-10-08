package dev.chungjungsoo.gptmobile.data.backup

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.json.Json

/** Decode/authenticate first, then migrate in an isolated current-schema snapshot. */
internal object LegacyBackupConverter {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun convert(
        input: InputStream,
        type: Int,
        password: String?,
        snapshot: ChatDatabaseV2,
        currentPreferences: Map<String, BackupValue>,
        currentShared: Map<String, Map<String, BackupValue>>,
        currentSecrets: Map<String, String>
    ): CompleteBackupManifest {
        val values = currentPreferences.toMutableMap()
        val secrets = mutableMapOf<String, String>()
        val sections = mutableSetOf<CompleteBackupSection>()
        fun favorites(groups: List<String>, messageGroups: Map<Int, String>) {
            if (groups.isNotEmpty()) values["favorite_groups_json"] = BackupValue("string", json.encodeToString(groups))
            if (messageGroups.isNotEmpty()) values["favorite_message_groups_json"] = BackupValue("string", json.encodeToString(messageGroups))
            if (groups.isNotEmpty() || messageGroups.isNotEmpty()) sections += CompleteBackupSection.SETTINGS
        }
        fun secured(platform: PlatformV2): PlatformV2 {
            val token = platform.token?.takeIf { it.isNotBlank() } ?: return platform.copy(token = null)
            val reference = platform.secretRef ?: "platform_${platform.uid}"
            secrets[reference] = Base64.getEncoder().encodeToString(token.toByteArray())
            return platform.copy(token = null, secretRef = reference)
        }
        suspend fun platform(platform: PlatformV2) {
            val previous = snapshot.platformDao().getPlatformByUid(platform.uid)
            val incoming = secured(platform).copy(id = previous?.id ?: 0)
            if (previous == null) snapshot.platformDao().addPlatform(incoming) else snapshot.platformDao().editPlatform(incoming)
        }
        suspend fun replaceConversations(rooms: List<ChatRoomV2>, messages: List<dev.chungjungsoo.gptmobile.data.database.entity.MessageV2>, models: List<dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2>) {
            require(rooms.all { it.id > 0 } && messages.all { it.id > 0 }) { "Legacy backup has invalid record IDs." }
            snapshot.chatRoomDao().getChatRooms().takeIf { it.isNotEmpty() }?.let { snapshot.chatRoomDao().deleteChatRooms(*it.toTypedArray()) }
            rooms.forEach { snapshot.chatRoomDao().addChatRoom(it.copy(draftAttachments = "[]")) }
            if (messages.isNotEmpty()) snapshot.messageDao().addMessages(*messages.map { it.copy(attachments = emptyList(), currentRunId = null, revisions = it.revisions.map { revision -> revision.copy(runId = null) }) }.toTypedArray())
            if (models.isNotEmpty()) snapshot.chatPlatformModelDao().upsertAll(*models.toTypedArray())
            sections += CompleteBackupSection.CONVERSATIONS
        }

        // Payloads are decoded before entering the snapshot transaction. A wrong password,
        // newer payload, corrupt record or foreign-key failure cannot modify live app data.
        when (type) {
            1 -> {
                val payload = AppBackupCrypto.decryptConfig(input, password)
                require(payload.version in 1..2) { "This configuration backup requires a newer app." }
                snapshot.withTransaction {
                    payload.platforms.forEach { platform(it) }
                    payload.toolConnections.forEach { item ->
                        val reference = item.connection.secretRef ?: "connection_${item.connection.connectionUid}"
                        val token = item.credentialPlaintext?.takeIf { it.isNotBlank() }
                        if (token != null) secrets[reference] = Base64.getEncoder().encodeToString(token.toByteArray())
                        snapshot.toolConnectionDao().upsertConnection(if (token == null) item.connection else item.connection.copy(secretRef = reference))
                    }
                    payload.agentToolBindings.forEach { snapshot.toolConnectionDao().insertBinding(it) }
                }
                if (payload.platforms.isNotEmpty()) sections += CompleteBackupSection.PLATFORMS
                if (payload.toolConnections.isNotEmpty() || payload.agentToolBindings.isNotEmpty()) sections += CompleteBackupSection.TOOLS
                val ui = payload.uiPreferences
                val theme = payload.theme
                if (ui != null || theme != null) {
                    sections += CompleteBackupSection.SETTINGS
                    values["theme_mode"] = BackupValue("int", (ui?.themeMode ?: theme!!.themeMode).toString())
                    values["dynamic_mode"] = BackupValue("int", if (ui?.dynamicTheme ?: theme!!.dynamicTheme) "1" else "0")
                    (ui?.customPrimaryArgb ?: theme?.customPrimaryArgb)?.let { values["custom_primary_argb"] = BackupValue("long", it.toString()) }
                    (ui?.customPalette ?: theme?.customPalette)?.let { values["custom_theme_palette"] = BackupValue("string", json.encodeToString(it)) }
                    ui?.let {
                        values["debug_mode"] = BackupValue("boolean", it.debugMode.toString())
                        values["local_runtime_backend"] = BackupValue("string", it.localRuntimeBackend)
                    }
                }
                favorites(payload.favoriteGroups, payload.messageGroups)
            }
            2 -> {
                val payload = AppBackupCrypto.decryptDatabase(input, password)
                require(payload.version in 1..2) { "This conversation backup requires a newer app." }
                snapshot.withTransaction { replaceConversations(payload.chatRooms, payload.messages, payload.chatPlatformModels) }
                favorites(payload.favoriteGroups, payload.messageGroups)
            }
            3 -> {
                val payload = AppBackupCrypto.decryptUserBackup(input, password)
                require(payload.version in 1..UserBackupData.BACKUP_VERSION) { "This user backup requires a newer app." }
                snapshot.withTransaction {
                    payload.platforms.forEach { platform(it) }
                    payload.toolConnections.forEach { snapshot.toolConnectionDao().upsertConnection(it) }
                    replaceConversations(payload.chatRooms, payload.messages, payload.models)
                }
                if (payload.platforms.isNotEmpty()) sections += CompleteBackupSection.PLATFORMS
                if (payload.toolConnections.isNotEmpty()) sections += CompleteBackupSection.TOOLS
                favorites(payload.favoriteGroups, payload.messageGroups)
            }
            4 -> {
                val payload = AppBackupCrypto.decryptFavorites(input, password)
                require(payload.version == 1) { "This favorites backup requires a newer app." }
                snapshot.withTransaction { payload.favoriteIds.filter { it > 0 }.forEach { snapshot.messageDao().updateFavorite(it, true) } }
                sections += CompleteBackupSection.CONVERSATIONS
                favorites(payload.favoriteGroups, payload.messageGroups)
            }
            else -> error("Unsupported legacy backup type.")
        }
        if (secrets.isNotEmpty()) sections += CompleteBackupSection.CREDENTIALS
        if (sections.isEmpty()) sections += CompleteBackupSection.SETTINGS
        val selection = CompleteBackupSelection(sections).normalized()
        snapshot.withTransaction { CompleteBackupDatabase.retainSections(snapshot.openHelper.writableDatabase, selection) }
        return CompleteBackupManifest(
            preferences = if (CompleteBackupSection.SETTINGS in sections) values else emptyMap(),
            sharedPreferences = if (CompleteBackupSection.SETTINGS in sections) currentShared else emptyMap(),
            secrets = if (CompleteBackupSection.CREDENTIALS in sections) currentSecrets + secrets else emptyMap(),
            sections = selection.sections.mapTo(linkedSetOf()) { it.name }
        )
    }

    fun upgrade(manifest: CompleteBackupManifest): CompleteBackupManifest {
        if (manifest.version == CURRENT_VERSION) return manifest
        val sections = if (manifest.version == 1 || manifest.sections.isEmpty()) {
            CompleteBackupSelection.ALL.sections
        } else {
            manifest.sections.flatMap { section ->
                when (section) {
                    "database" -> listOf(CompleteBackupSection.CONVERSATIONS, CompleteBackupSection.PLATFORMS, CompleteBackupSection.TOOLS, CompleteBackupSection.LOCAL_MODELS, CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.STATISTICS)
                    "settings" -> listOf(CompleteBackupSection.SETTINGS)
                    "credentials" -> listOf(CompleteBackupSection.CREDENTIALS)
                    "app_files" -> listOf(CompleteBackupSection.ATTACHMENTS, CompleteBackupSection.LOCAL_MODELS)
                    else -> listOfNotNull(runCatching { CompleteBackupSection.valueOf(section) }.getOrNull())
                }
            }.toSet()
        }
        return manifest.copy(version = CURRENT_VERSION, sections = CompleteBackupSelection(sections).normalized().sections.mapTo(linkedSetOf()) { it.name })
    }

    const val CURRENT_VERSION = 3
}
