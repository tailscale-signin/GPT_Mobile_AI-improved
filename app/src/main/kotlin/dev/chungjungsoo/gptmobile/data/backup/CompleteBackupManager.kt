package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class CompleteBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: ChatDatabaseV2,
    dataStore: DataStore<Preferences>,
    private val secretVault: SecretVault,
    private val settings: SettingRepository,
    private val legacy: AppBackupManager
) {
    private val mutex = Mutex()
    private val preferences = CompleteBackupPreferences(context, dataStore)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private fun files() = CompleteBackupFiles(mapOf("internal" to context.filesDir, "external" to (context.getExternalFilesDir(null) ?: context.filesDir)))
    fun getBackupStatus() = legacy.getBackupStatus()

    suspend fun backup(
        uri: Uri,
        options: CompleteBackupOptions = CompleteBackupOptions(),
        password: String? = null
    ): BackupRestoreResult = operation { work ->
        require(options.hasAnySelection) { "Select at least one backup section." }
        val storage = files()
        val sources = if (options.appFiles) storage.collect() else mutableMapOf()

        if (options.database) {
            val dbFile = File(work, "database.sqlite")
            database.withTransaction {
                ensureIdle(restoring = false)
                CompleteBackupDatabase.snapshot(database.openHelper.writableDatabase, dbFile)
            }
            val snapshot = openSnapshot(dbFile)
            try {
                if (options.appFiles) {
                    validateModels(snapshot.openHelper.writableDatabase, sources.keys)
                    rewriteAttachments(snapshot.openHelper.writableDatabase) { storage.archivePath(it, sources) }
                } else {
                    // Avoid restoring database rows that point at files deliberately omitted
                    // from this backup.
                    snapshot.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET attachments = '[]'")
                    snapshot.openHelper.writableDatabase.execSQL("DELETE FROM local_models")
                }
            } finally {
                snapshot.close()
            }
            sources["database.sqlite"] = dbFile
        }

        val manifest = CompleteBackupManifest(
            preferences = if (options.settings) preferences.read() else emptyMap(),
            sharedPreferences = if (options.settings) preferences.readShared() else emptyMap(),
            secrets = if (options.credentials) readSecrets() else emptyMap(),
            files = sources.mapValues { it.value.length() },
            sections = options.sections()
        )
        val archive = File(work, "archive.zip")
        CompleteBackupArchive.write(archive, manifest, sources)

        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            val protectionPassword = password?.takeIf(String::isNotBlank)
            if (protectionPassword != null) {
                CompleteBackupCrypto.encrypt(archive, output, protectionPassword)
            } else {
                archive.inputStream().buffered().use { input -> input.copyTo(output) }
            }
        } ?: error("Could not open the backup destination.")

        legacy.recordBackupMetadata()
        BackupRestoreResult(
            true,
            if (password.isNullOrBlank()) "Backup saved." else "Password-encrypted backup saved."
        )
    }

    suspend fun restore(
        uri: Uri,
        legacyPassword: String? = null,
        options: CompleteBackupOptions = CompleteBackupOptions()
    ): BackupRestoreResult = operation { work ->
        require(options.hasAnySelection) { "Select at least one restore section." }
        ensureIdle(restoring = true)
        val archive = File(work, "archive.zip")
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            input.mark(64)
            val header = ByteArray(9)
            val headerBytes = input.read(header)
            input.reset()
            if (headerBytes >= 9 && header.copyOfRange(0, 7).decodeToString() == "GPTBKUP") {
                val result = when (header[8].toInt()) {
                    1 -> legacy.restoreConfiguration(uri, legacyPassword?.takeIf(String::isNotEmpty))
                    2 -> legacy.restoreDatabase(uri, legacyPassword?.takeIf(String::isNotEmpty))
                    4 -> legacy.importFavorites(uri, legacyPassword?.takeIf(String::isNotEmpty))
                    else -> error("Unsupported legacy backup type.")
                }
                settings.invalidatePlatformCache()
                return@operation result.copy(message = "Legacy backup: ${result.message}")
            }
            val isLegacyComplete = headerBytes >= 8 && header.copyOfRange(0, 8).decodeToString() == "GPTFULL1"
            val isPasswordlessComplete = headerBytes >= 8 && header.copyOfRange(0, 8).decodeToString() == "GPTFULL2"
            val isZip = headerBytes >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
            when {
                isPasswordlessComplete -> {
                    val backupKey = requireExistingBackupKey()
                    try {
                        CompleteBackupCrypto.decryptWithKey(input, archive, backupKey, work.usableSpace - RESERVE)
                    } finally {
                        backupKey.fill(0)
                    }
                }
                isLegacyComplete -> {
                    val password = legacyPassword?.takeIf(String::isNotBlank)
                        ?: error("This older backup is encrypted. Enter its original password.")
                    CompleteBackupCrypto.decrypt(input, archive, password, work.usableSpace - RESERVE)
                }
                // Transitional development builds briefly wrote a plain ZIP.
                // Keep it readable so those backups are not stranded; all new
                // backups are GPTFULL2 encrypted.
                isZip -> copyArchiveWithLimit(input, archive, work.usableSpace - RESERVE)
                else -> error("Select a GPT Mobile backup file.")
            }
        } ?: error("Could not read the backup file.")
        val staging = File(work, "files").apply { mkdirs() }
        val manifest = CompleteBackupArchive.read(archive, staging, work.usableSpace - RESERVE)
        val availableSections = if (manifest.version == 1 || manifest.sections.isEmpty()) {
            CompleteBackupOptions().sections()
        } else {
            manifest.sections
        }

        val restoreDatabase = options.database &&
            CompleteBackupOptions.SECTION_DATABASE in availableSections &&
            "database.sqlite" in manifest.files
        val restoreSettings = options.settings &&
            CompleteBackupOptions.SECTION_SETTINGS in availableSections
        val restoreCredentials = options.credentials &&
            CompleteBackupOptions.SECTION_CREDENTIALS in availableSections
        val restoreFiles = options.appFiles &&
            CompleteBackupOptions.SECTION_APP_FILES in availableSections

        if (restoreSettings) {
            preferences.validate(manifest.preferences, manifest.sharedPreferences)
        }
        if (restoreCredentials) {
            validateSecrets(manifest.secrets)
        }

        val storage = files()
        val paths = if (restoreFiles) manifest.files.keys - "database.sqlite" else emptySet()
        require(paths.map(storage::target).toSet().size == paths.size) { "Conflicting backup file locations." }

        val snapshot = if (restoreDatabase) openSnapshot(File(staging, "database.sqlite")) else null
        try {
            val source = snapshot?.openHelper?.writableDatabase
            if (source != null) {
                CompleteBackupDatabase.validate(source, database.openHelper.writableDatabase)
                if (restoreFiles) {
                    validateModels(source, paths)
                    rewriteAttachments(source) { path ->
                        if (path.isBlank()) {
                            path
                        } else {
                            require(path in paths) { "The backup is missing an attachment." }
                            storage.target(path).absolutePath
                        }
                    }
                } else {
                    source.execSQL("UPDATE messages_v2 SET attachments = '[]'")
                    source.execSQL("DELETE FROM local_models")
                }
            }

            val oldPreferences = if (restoreSettings) preferences.read() else emptyMap()
            val oldShared = if (restoreSettings) preferences.readShared() else emptyMap()
            val oldSecrets = if (restoreCredentials) readSecrets() else emptyMap()
            val replacement = storage.replacement(staging, paths)

            try {
                database.withTransaction {
                    ensureIdle(restoring = true)
                    if (source != null) {
                        CompleteBackupDatabase.restore(source, database.openHelper.writableDatabase)
                    }
                    if (restoreFiles) replacement.apply()
                    if (restoreCredentials) replaceSecrets(manifest.secrets)
                    if (restoreSettings) preferences.replace(manifest.preferences, manifest.sharedPreferences)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    if (restoreFiles) runCatching { replacement.rollback() }
                    if (restoreCredentials) runCatching { replaceSecrets(oldSecrets) }
                    if (restoreSettings) runCatching { preferences.replace(oldPreferences, oldShared) }
                }
                throw error
            }
            if (restoreFiles) replacement.cleanup()
            settings.invalidatePlatformCache()
        } finally {
            snapshot?.close()
        }

        val restored = buildList {
            if (restoreDatabase) add("conversations & app data")
            if (restoreSettings) add("settings")
            if (restoreCredentials) add("credentials")
            if (restoreFiles) add("app files")
        }
        BackupRestoreResult(
            true,
            if (restored.isEmpty()) "None of the selected sections exist in this backup."
            else "Restored: ${restored.joinToString()}."
        )
    }

    suspend fun requiresPassword(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val header = ByteArray(9)
            val count = input.read(header)
            when {
                count >= 8 && header.copyOfRange(0, 8).decodeToString() == "GPTFULL1" -> true
                count >= 9 &&
                    header.copyOfRange(0, 7).decodeToString() == "GPTBKUP" &&
                    header[7].toInt() == 2 -> true
                else -> false
            }
        } ?: false
    }

    private fun copyArchiveWithLimit(input: java.io.InputStream, target: File, maxBytes: Long) {
        require(maxBytes > 0) { "Insufficient free space to restore the backup." }
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "Backup is too large for the available storage." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun openSnapshot(file: File): ChatDatabaseV2 = Room.databaseBuilder(context, ChatDatabaseV2::class.java, file.absolutePath)
        .addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .build()

    private fun rewriteAttachments(db: SupportSQLiteDatabase, transform: (String) -> String) {
        db.query("SELECT message_id, attachments FROM messages_v2").use { rows ->
            while (rows.moveToNext()) {
                val encoded = rows.getString(1).orEmpty().ifBlank { "[]" }
                val attachments = json.decodeFromString<List<ChatAttachment>>(encoded).map {
                    it.copy(localFilePath = transform(it.localFilePath), preparedFilePath = transform(it.preparedFilePath))
                }
                db.execSQL("UPDATE messages_v2 SET attachments = ? WHERE message_id = ?", arrayOf<Any>(json.encodeToString(attachments), rows.getInt(0)))
            }
        }
    }

    private fun validateModels(db: SupportSQLiteDatabase, paths: Set<String>) {
        db.query("SELECT relative_directory, file_name, status FROM local_models").use { rows ->
            while (rows.moveToNext()) {
                val path = "${rows.getString(0)}/${rows.getString(1)}"
                require(path.startsWith("models/")) { "Unsupported model location in backup." }
                CompleteBackupArchive.validatePath("external/$path")
                if (rows.getString(2) == "READY") {
                    require("internal/$path" in paths || "external/$path" in paths) { "A downloaded model is missing. Remove it or download it again before backing up." }
                }
            }
        }
    }

    private suspend fun readSecrets(): Map<String, String> {
        val refs = (
            secretVault.references() +
                database.platformDao().getPlatforms().mapNotNull { it.secretRef } +
                database.toolConnectionDao().getAllConnections().mapNotNull { it.secretRef }
            ).filterNot { it == BACKUP_KEY_REF }
        return buildMap {
            refs.forEach { reference ->
                secretVault.read(reference)?.let { bytes ->
                    try {
                        put(reference, Base64.getEncoder().encodeToString(bytes))
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }
    }

    private fun validateSecrets(values: Map<String, String>) {
        values.forEach { (reference, encoded) ->
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(reference)) { "Invalid credential reference." }
            val bytes = Base64.getDecoder().decode(encoded)
            try {
                require(bytes.size <= 64 * 1024) { "Invalid credential size." }
            } finally {
                bytes.fill(0)
            }
        }
    }

    private suspend fun replaceSecrets(values: Map<String, String>) {
        values.forEach { (reference, encoded) ->
            val bytes = Base64.getDecoder().decode(encoded)
            try {
                secretVault.put(reference, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        (secretVault.references() - values.keys - BACKUP_KEY_REF).forEach { secretVault.delete(it) }
    }

    private suspend fun getOrCreateBackupKey(): ByteArray {
        secretVault.read(BACKUP_KEY_REF)?.let { existing ->
            if (existing.size == BACKUP_KEY_BYTES) return existing
            existing.fill(0)
            secretVault.delete(BACKUP_KEY_REF)
        }

        val generated = ByteArray(BACKUP_KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            secretVault.put(BACKUP_KEY_REF, generated)
            return generated.copyOf()
        } finally {
            generated.fill(0)
        }
    }

    private suspend fun requireExistingBackupKey(): ByteArray {
        val key = secretVault.read(BACKUP_KEY_REF)
            ?: error(
                "This passwordless encrypted backup is protected by another app installation. " +
                    "Restore it from the installation that created it, or use an older password-based backup."
            )
        if (key.size != BACKUP_KEY_BYTES) {
            key.fill(0)
            error("The passwordless backup encryption key is invalid.")
        }
        return key
    }

    private fun ensureIdle(restoring: Boolean) {
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM local_models WHERE status = 'DOWNLOADING'").use {
            it.moveToFirst()
            require(it.getInt(0) == 0) { "Finish or cancel model downloads before backing up or restoring." }
        }
        if (restoring) {
            db.query("SELECT COUNT(*) FROM agent_runs WHERE status IN ('QUEUED', 'RUNNING')").use {
                it.moveToFirst()
                require(it.getInt(0) == 0) { "Stop active chats before restoring." }
            }
        }
    }

    private suspend fun operation(block: suspend (File) -> BackupRestoreResult): BackupRestoreResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val work = File(context.cacheDir, "complete-backup-${UUID.randomUUID()}")
            try {
                check(work.mkdirs()) { "Could not create temporary backup storage." }
                block(work)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AEADBadTagException) {
                BackupRestoreResult(
                    false,
                    "The encrypted backup could not be authenticated. It may be damaged, from another installation, or use a different legacy password."
                )
            } catch (error: Exception) {
                BackupRestoreResult(false, error.localizedMessage?.takeIf(String::isNotBlank) ?: "The backup could not be read or written.")
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private companion object {
        const val RESERVE = 16L * 1024 * 1024
        const val BACKUP_KEY_BYTES = 32
        const val BACKUP_KEY_REF = "complete_backup_master_v2"
    }
}
