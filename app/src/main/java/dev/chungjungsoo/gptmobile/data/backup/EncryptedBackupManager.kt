package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encapsulates full user configuration state for backup & restore:
 * Themes, models, MCP configurations, custom servers, and UI/runtime settings.
 */
@Serializable
data class UserConfigurationBackup(
    val version: Int = CURRENT_VERSION,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val themeSettings: ThemeConfiguration = ThemeConfiguration(),
    val modelConfigurations: Map<String, String> = emptyMap(),
    val mcpConfigurations: Map<String, String> = emptyMap(),
    val generalSettings: Map<String, String> = emptyMap()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class ThemeConfiguration(
    val themeMode: Int = 0,
    val dynamicTheme: Boolean = true,
    val trueBlack: Boolean = false
)

/**
 * Manager providing encrypted backup and restore for:
 * 1. User Configuration (Themes, Models, MCP, settings).
 * 2. Conversations Database (all chat history, messages, and favourites).
 *
 * Both backups are encrypted with AES-256-GCM using PBKDF2 key derivation from a user passphrase.
 */
class EncryptedBackupManager(
    private val iterationCount: Int = DEFAULT_PBKDF2_ITERATIONS
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val secureRandom = SecureRandom()

    companion object {
        private const val MAGIC_CONFIG = "GPTCFG_V1:"
        private const val MAGIC_DATABASE = "GPTDB_V1::"
        private const val MAGIC_LENGTH = 10
        private const val SALT_SIZE_BYTES = 16
        private const val IV_SIZE_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH_BITS = 256
        const val DEFAULT_PBKDF2_ITERATIONS = 65_536

        // SQLite magic header: "SQLite format 3\000"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Backs up user configuration to an encrypted local file.
     */
    fun backupConfiguration(
        config: UserConfigurationBackup,
        targetFile: File,
        passphrase: CharArray
    ): Result<File> = runCatching {
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty" }
        val serializedJson = json.encodeToString(config)
        val plaintextBytes = serializedJson.toByteArray(Charsets.UTF_8)

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            fos.write(MAGIC_CONFIG.toByteArray(Charsets.US_ASCII))

            val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            fos.write(salt)

            val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            fos.write(iv)

            val key = deriveKey(passphrase, salt, iterationCount)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val cipherBytes = cipher.doFinal(plaintextBytes)
            fos.write(cipherBytes)
            fos.flush()
        }
        targetFile
    }

    /**
     * Restores user configuration from an encrypted local file.
     */
    fun restoreConfiguration(
        sourceFile: File,
        passphrase: CharArray
    ): Result<UserConfigurationBackup> = runCatching {
        require(sourceFile.exists() && sourceFile.canRead()) { "Backup file does not exist or is unreadable" }
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty" }

        FileInputStream(sourceFile).use { fis ->
            val magicBytes = ByteArray(MAGIC_LENGTH)
            val readMagic = fis.read(magicBytes)
            if (readMagic != MAGIC_LENGTH || !magicBytes.contentEquals(MAGIC_CONFIG.toByteArray(Charsets.US_ASCII))) {
                throw IllegalArgumentException("Invalid or unsupported configuration backup file format")
            }

            val salt = ByteArray(SALT_SIZE_BYTES)
            if (fis.read(salt) != SALT_SIZE_BYTES) throw IllegalArgumentException("Corrupted backup: salt missing")

            val iv = ByteArray(IV_SIZE_BYTES)
            if (fis.read(iv) != IV_SIZE_BYTES) throw IllegalArgumentException("Corrupted backup: IV missing")

            val ciphertext = fis.readBytes()
            val key = deriveKey(passphrase, salt, iterationCount)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            json.decodeFromString<UserConfigurationBackup>(jsonString)
        }
    }

    /**
     * Backs up the SQLite database (conversations + favourites) to an encrypted local file.
     */
    fun backupDatabase(
        sourceDbFile: File,
        targetFile: File,
        passphrase: CharArray
    ): Result<File> = runCatching {
        require(sourceDbFile.exists() && sourceDbFile.canRead()) { "Database file does not exist or cannot be read" }
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty" }

        val dbBytes = FileInputStream(sourceDbFile).use { it.readBytes() }
        if (dbBytes.size < SQLITE_HEADER.size || !dbBytes.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) {
            throw IllegalArgumentException("Source file is not a valid SQLite database")
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            fos.write(MAGIC_DATABASE.toByteArray(Charsets.US_ASCII))

            val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            fos.write(salt)

            val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
            fos.write(iv)

            val key = deriveKey(passphrase, salt, iterationCount)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val cipherBytes = cipher.doFinal(dbBytes)
            fos.write(cipherBytes)
            fos.flush()
        }
        targetFile
    }

    /**
     * Restores the SQLite database from an encrypted local file.
     * Decrypts, verifies SQLite integrity header, and replaces the target database file atomically,
     * removing stale -wal and -shm journal files.
     */
    fun restoreDatabase(
        sourceEncryptedFile: File,
        targetDbFile: File,
        passphrase: CharArray
    ): Result<File> = runCatching {
        require(sourceEncryptedFile.exists() && sourceEncryptedFile.canRead()) { "Encrypted database backup not found" }
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty" }

        val decryptedBytes = FileInputStream(sourceEncryptedFile).use { fis ->
            val magicBytes = ByteArray(MAGIC_LENGTH)
            val readMagic = fis.read(magicBytes)
            if (readMagic != MAGIC_LENGTH || !magicBytes.contentEquals(MAGIC_DATABASE.toByteArray(Charsets.US_ASCII))) {
                throw IllegalArgumentException("Invalid or unsupported database backup file format")
            }

            val salt = ByteArray(SALT_SIZE_BYTES)
            if (fis.read(salt) != SALT_SIZE_BYTES) throw IllegalArgumentException("Corrupted backup: salt missing")

            val iv = ByteArray(IV_SIZE_BYTES)
            if (fis.read(iv) != IV_SIZE_BYTES) throw IllegalArgumentException("Corrupted backup: IV missing")

            val ciphertext = fis.readBytes()
            val key = deriveKey(passphrase, salt, iterationCount)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            cipher.doFinal(ciphertext)
        }

        // Verify SQLite header integrity
        if (decryptedBytes.size < SQLITE_HEADER.size ||
            !decryptedBytes.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)
        ) {
            throw IllegalStateException("Decrypted payload is not a valid SQLite database")
        }

        // Clean up any stale companion WAL or SHM files
        targetDbFile.parentFile?.mkdirs()
        File("${targetDbFile.path}-wal").delete()
        File("${targetDbFile.path}-shm").delete()

        // Atomically replace target database
        val tempFile = File.createTempFile("db_restore_", ".tmp", targetDbFile.parentFile)
        try {
            FileOutputStream(tempFile).use { it.write(decryptedBytes) }
            if (targetDbFile.exists()) {
                targetDbFile.delete()
            }
            if (!tempFile.renameTo(targetDbFile)) {
                tempFile.copyTo(targetDbFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        targetDbFile
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
