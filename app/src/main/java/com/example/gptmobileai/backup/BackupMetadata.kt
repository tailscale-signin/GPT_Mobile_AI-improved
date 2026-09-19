package com.example.gptmobileai.backup

import java.io.File
import java.util.UUID

data class BackupMetadata(
    val id: String = UUID.randomUUID().toString(),
    val version: String = "1.0",
    val timestamp: Long = System.currentTimeMillis(),
    val isIncremental: Boolean = false,
    val baseBackupId: String? = null,
    val checksum: String = "",  // SHA-256 of original data before encryption
    val chatCount: Int = 0,
    val secretCount: Int = 0,
    val preferenceCount: Int = 0,
    val sizeBytes: Long = 0L,
    val isCompressed: Boolean = false
)

data class BackupVersion(
    val id: String,
    val filename: String,
    val timestamp: Long,
    val size: Long,
    val isLatest: Boolean,
    val chatCount: Int,
    val secretCount: Int,
    val preferenceCount: Int,
    val metadataFile: File? = null
)

sealed interface ChangeRecord {
    data class DatabaseChange(val table: String, val rowsChanged: Int) : ChangeRecord
    data class SecretChange(val key: String, val added: Boolean) : ChangeRecord
    data class PreferenceChange(val key: String, val oldValue: Any?, val newValue: Any?) : ChangeRecord
}

data class BackupPreview(
    val timestamp: Long,
    val isIncremental: Boolean,
    val chatCount: Int,
    val secretCount: Int,
    val preferenceCount: Int,
    val sizeBytes: Long,
    val changeLog: List<ChangeRecord> = emptyList()
)
