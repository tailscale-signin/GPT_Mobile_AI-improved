package com.example.gptmobileai.backup

import java.io.File
import java.security.MessageDigest
import java.util.Base64

object IntegrityVerifier {

    fun computeDataChecksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return Base64.getEncoder().encodeToString(md.digest())
    }

    fun computeDataChecksum(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return Base64.getEncoder().encodeToString(digest)
    }

    fun readStoredChecksum(backupFile: File): String? {
        val metadataFile = File(backupFile.parentFile, "${backupFile.nameWithoutExtension}.meta")

        if (!metadataFile.exists()) return null

        return try {
            metadataFile.readLines().firstOrNull { it.startsWith("checksum=") }
                ?.substringAfter("=")?.trim()
        } catch (e: Exception) {
            null
        }
    }

    fun verifyBackupIntegrity(backupFile: File): BackupMetadata? {
        val storedChecksum = readStoredChecksum(backupFile) ?: return null

        val computedChecksum = computeDataChecksum(backupFile)

        return if (storedChecksum == computedChecksum) {
            BackupMetadata(id = backupFile.nameWithoutExtension, checksum = computedChecksum)
        } else {
            throw BackupIntegrityException("Backup checksum mismatch: expected $storedChecksum, got $computedChecksum")
        }
    }
}

class BackupIntegrityException(message: String) : Exception(message)
