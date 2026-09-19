package com.example.gptmobileai.backup

import android.content.Context
import java.io.File

class BackupVersionManager(private val context: Context) {

    private val backupDir = File(context.filesDir, "backups")

    fun listBackupVersions(): List<BackupVersion> {
        val versions = mutableListOf<BackupVersion>()

        if (!backupDir.exists()) return versions

        backupDir.listFiles()?.forEach { backupFile ->
            if (backupFile.isFile && backupFile.extension == "bin") {
                val metadataFile = File(backupFile.parentFile, "${backupFile.nameWithoutExtension}.meta")

                val metadata = try {
                    if (metadataFile.exists()) readMetadataFromText(metadataFile.readText()) else null
                } catch (e: Exception) {
                    null
                }

                if (metadata != null) {
                    versions.add(
                        BackupVersion(
                            id = metadata.id,
                            filename = backupFile.name,
                            timestamp = metadata.timestamp,
                            size = backupFile.length(),
                            isLatest = backupFile.name == getLatestBackupName(),
                            chatCount = metadata.chatCount,
                            secretCount = metadata.secretCount,
                            preferenceCount = metadata.preferenceCount,
                            metadataFile = metadataFile
                        )
                    )
                }
            }
        }

        return versions.sortedByDescending { it.timestamp }
    }

    private fun readMetadataFromText(text: String): BackupMetadata? {
        val lines = text.lines()
        val metadata = mutableMapOf<String, String>()

        lines.forEach { line ->
            if (line.contains("=")) {
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                metadata[key] = value
            }
        }

        return BackupMetadata(
            id = metadata["id"] ?: "",
            version = metadata["version"] ?: "1.0",
            timestamp = metadata["timestamp"]?.toLongOrNull() ?: 0L,
            checksum = metadata["checksum"] ?: "",
            sizeBytes = metadata["sizeBytes"]?.toLongOrNull() ?: 0L,
            chatCount = metadata["chatCount"]?.toIntOrNull() ?: 0,
            secretCount = metadata["secretCount"]?.toIntOrNull() ?: 0,
            preferenceCount = metadata["preferenceCount"]?.toIntOrNull() ?: 0
        )
    }

    private fun getLatestBackupName(): String {
        val latestFile = backupDir.listFiles()?.filter { it.extension == "bin" }?.maxByOrNull { it.lastModified() }
        return latestFile?.name ?: ""
    }
}
