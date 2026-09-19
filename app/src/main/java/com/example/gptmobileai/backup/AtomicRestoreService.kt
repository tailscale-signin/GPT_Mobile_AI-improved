package com.example.gptmobileai.backup

import android.content.Context
import java.io.File

class AtomicRestoreService(private val context: Context) {

    private val tempDir = File(context.cacheDir, "backup_restore_temp")

    fun createTempDirectory(): File {
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        return tempDir
    }

    suspend fun backupCurrentState(tempDir: File): List<ShadowCopy> {
        val shadowCopies = mutableListOf<ShadowCopy>()

        // Backup chat database
        try {
            val dbFile = context.getDatabasePath("chat_database.db")
            if (dbFile.exists()) {
                val shadowDb = File(tempDir, "chat_database_shadow.db")
                dbFile.inputStream().use { input ->
                    shadowDb.outputStream().use { output -> input.copyTo(output) }
                }
                shadowCopies.add(ShadowCopy("chat_database", shadowDb))
            }
        } catch (e: Exception) {
            // Database might not exist yet, skip
        }

        // Backup secret vault
        try {
            val vaultFile = context.filesDir.resolve("secret_vault.bin")
            if (vaultFile.exists()) {
                val shadowVault = File(tempDir, "secret_vault_shadow.bin")
                vaultFile.inputStream().use { input ->
                    shadowVault.outputStream().use { output -> input.copyTo(output) }
                }
                shadowCopies.add(ShadowCopy("secret_vault", shadowVault))
            }
        } catch (e: Exception) {
            // Vault might not exist yet, skip
        }

        // Backup preferences
        try {
            val prefsFile = File(context.filesDir, "datastore/preferences.pb")
            val targetPrefsFile = if (prefsFile.exists()) prefsFile else context.filesDir.resolve("preferences.pb")
            if (targetPrefsFile.exists()) {
                val shadowPrefs = File(tempDir, "preferences_shadow.pb")
                targetPrefsFile.inputStream().use { input ->
                    shadowPrefs.outputStream().use { output -> input.copyTo(output) }
                }
                shadowCopies.add(ShadowCopy("preferences", shadowPrefs))
            }
        } catch (e: Exception) {
            // Preferences might not exist yet, skip
        }

        return shadowCopies
    }

    suspend fun rollbackToShadowCopies(shadowCopies: List<ShadowCopy>) {
        shadowCopies.forEach { copy ->
            try {
                val targetFile = getTargetPathForShadow(copy.name)
                targetFile.parentFile?.mkdirs()
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                copy.file.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getTargetPathForShadow(shadowName: String): File {
        return when (shadowName) {
            "chat_database" -> context.getDatabasePath("chat_database.db")
            "secret_vault" -> context.filesDir.resolve("secret_vault.bin")
            "preferences" -> {
                val datastoreDir = File(context.filesDir, "datastore")
                if (datastoreDir.exists()) File(datastoreDir, "preferences.pb") else context.filesDir.resolve("preferences.pb")
            }
            "restored_data" -> context.filesDir.resolve("restored_data.bin")
            else -> throw IllegalArgumentException("Unknown shadow copy: $shadowName")
        }
    }

    fun cleanupTempDirectory(tempDir: File) {
        tempDir.deleteRecursively()
    }
}

data class ShadowCopy(val name: String, val file: File)
