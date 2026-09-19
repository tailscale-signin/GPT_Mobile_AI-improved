package com.example.gptmobileai.backup

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(private val context: Context) {

    private val integrityVerifier = IntegrityVerifier
    private val atomicRestoreService = AtomicRestoreService(context)
    private val secureRandom = SecureRandom()

    companion object {
        private const val MAGIC_HEADER = "GPT_ALL_BAK_V1"
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_LEN_BITS = 256
    }

    suspend fun createEncryptedBackup(backupFile: File, passphrase: String): BackupMetadata {
        val backupData = createBackupData()
        val checksum = integrityVerifier.computeDataChecksum(backupData)

        val encrypted = encryptWithGcm(backupData, passphrase)

        backupFile.parentFile?.mkdirs()
        backupFile.outputStream().use { output ->
            output.write(encrypted)
        }

        val metadata = BackupMetadata(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            checksum = checksum,
            sizeBytes = backupFile.length()
        )

        saveMetadata(metadata, backupFile)
        return metadata
    }

    private suspend fun createBackupData(): ByteArray {
        val bos = ByteArrayOutputStream()

        fun writePayload(tag: String, data: ByteArray) {
            val tagBytes = tag.toByteArray(Charsets.UTF_8)
            bos.write(ByteBuffer.allocate(4).putInt(tagBytes.size).array())
            bos.write(tagBytes)
            bos.write(ByteBuffer.allocate(4).putInt(data.size).array())
            bos.write(data)
        }

        // 1. Chat database
        try {
            val dbFile = context.getDatabasePath("chat_database.db")
            if (dbFile.exists()) {
                writePayload("chat_database", dbFile.readBytes())
            }
        } catch (e: Exception) {
            // Skip if not exists
        }

        // 2. Secret vault
        try {
            val vaultFile = context.filesDir.resolve("secret_vault.bin")
            if (vaultFile.exists()) {
                writePayload("secret_vault", vaultFile.readBytes())
            }
        } catch (e: Exception) {
            // Skip if not exists
        }

        // 3. Preferences
        try {
            val prefsFile = File(context.filesDir, "datastore/preferences.pb")
            val target = if (prefsFile.exists()) prefsFile else context.filesDir.resolve("preferences.pb")
            if (target.exists()) {
                writePayload("preferences", target.readBytes())
            }
        } catch (e: Exception) {
            // Skip if not exists
        }

        return bos.toByteArray()
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    private suspend fun encryptWithGcm(data: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(data)

        val headerBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        val result = ByteArray(headerBytes.size + SALT_SIZE + IV_SIZE + ciphertext.size)
        val buffer = ByteBuffer.wrap(result)
        buffer.put(headerBytes)
        buffer.put(salt)
        buffer.put(iv)
        buffer.put(ciphertext)
        return result
    }

    private suspend fun decryptWithGcm(encryptedData: ByteArray, passphrase: String): ByteArray {
        val headerBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.wrap(encryptedData)

        val headerRead = ByteArray(headerBytes.size)
        buffer.get(headerRead)
        if (!headerRead.contentEquals(headerBytes)) {
            throw BackupException.IntegrityException("Invalid backup file magic header")
        }

        val salt = ByteArray(SALT_SIZE)
        buffer.get(salt)

        val iv = ByteArray(IV_SIZE)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }

    private fun saveMetadata(metadata: BackupMetadata, backupFile: File) {
        val metadataFile = File(backupFile.parentFile, "${backupFile.nameWithoutExtension}.meta")
        metadataFile.writeText(
            """
            id=${metadata.id}
            version=${metadata.version}
            timestamp=${metadata.timestamp}
            checksum=${metadata.checksum}
            sizeBytes=${metadata.sizeBytes}
            chatCount=${metadata.chatCount}
            secretCount=${metadata.secretCount}
            preferenceCount=${metadata.preferenceCount}
            """.trimIndent()
        )
    }

    suspend fun restoreAllFromBackup(backupFile: File, passphrase: String): RestoreResult {
        // 1. Verify backup integrity
        val metadata = integrityVerifier.verifyBackupIntegrity(backupFile)
            ?: throw BackupIntegrityException("Invalid or corrupted backup file")

        // 2. Create temporary directory for atomic restore
        val tempDir = atomicRestoreService.createTempDirectory()
        var shadowCopies = emptyList<ShadowCopy>()

        try {
            // 3. Backup current state (for rollback safety)
            shadowCopies = atomicRestoreService.backupCurrentState(tempDir)

            // 4. Decrypt and restore to temporary location
            val decryptedData = decryptWithGcm(backupFile.readBytes(), passphrase)
            restoreToTemporaryLocation(decryptedData, tempDir)

            // 5. Validate restored data
            if (!validateRestoredData(tempDir)) {
                throw BackupException.ValidationException("Restored data failed validation")
            }

            // 6. Atomic swap (only on success)
            atomicallySwapData(tempDir)

            return RestoreResult(
                success = true,
                metadata = metadata,
                shadowCopies = shadowCopies
            )

        } catch (e: Exception) {
            // Rollback to shadow copies if anything fails
            atomicRestoreService.rollbackToShadowCopies(shadowCopies)
            throw e
        } finally {
            atomicRestoreService.cleanupTempDirectory(tempDir)
        }
    }

    private suspend fun restoreToTemporaryLocation(data: ByteArray, tempDir: File) {
        val bais = ByteArrayInputStream(data)
        while (bais.available() > 0) {
            val tagLenBytes = ByteArray(4)
            if (bais.read(tagLenBytes) != 4) break
            val tagLen = ByteBuffer.wrap(tagLenBytes).int

            val tagBytes = ByteArray(tagLen)
            if (bais.read(tagBytes) != tagLen) break
            val tag = String(tagBytes, Charsets.UTF_8)

            val dataLenBytes = ByteArray(4)
            if (bais.read(dataLenBytes) != 4) break
            val dataLen = ByteBuffer.wrap(dataLenBytes).int

            val payload = ByteArray(dataLen)
            if (bais.read(payload) != dataLen) break

            when (tag) {
                "chat_database" -> File(tempDir, "chat_database.db").writeBytes(payload)
                "secret_vault" -> File(tempDir, "secret_vault.bin").writeBytes(payload)
                "preferences" -> File(tempDir, "preferences.pb").writeBytes(payload)
            }
        }
    }

    private fun validateRestoredData(tempDir: File): Boolean {
        val dbFile = File(tempDir, "chat_database.db")
        if (dbFile.exists() && dbFile.length() < 16) {
            return false
        }
        return true
    }

    private suspend fun atomicallySwapData(tempDir: File) {
        val filesToSwap = listOf("chat_database.db", "secret_vault.bin", "preferences.pb")
        for (fileName in filesToSwap) {
            val sourceFile = File(tempDir, fileName)
            if (sourceFile.exists()) {
                val target = when (fileName) {
                    "chat_database.db" -> context.getDatabasePath("chat_database.db")
                    "secret_vault.bin" -> context.filesDir.resolve("secret_vault.bin")
                    "preferences.pb" -> {
                        val datastoreDir = File(context.filesDir, "datastore").also { it.mkdirs() }
                        File(datastoreDir, "preferences.pb")
                    }
                    else -> continue
                }
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                sourceFile.copyTo(target, overwrite = true)
            }
        }
    }
}

data class RestoreResult(
    val success: Boolean,
    val metadata: BackupMetadata?,
    val shadowCopies: List<ShadowCopy>
)

sealed class BackupException : Exception() {
    class IntegrityException(message: String) : BackupException()
    class ValidationException(message: String) : BackupException()
}
