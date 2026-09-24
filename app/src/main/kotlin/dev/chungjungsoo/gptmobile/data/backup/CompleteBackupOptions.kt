package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class CompleteBackupOptions(
    val database: Boolean = true,
    val settings: Boolean = true,
    val credentials: Boolean = true,
    val appFiles: Boolean = true
) {
    val hasAnySelection: Boolean
        get() = database || settings || credentials || appFiles

    fun sections(): Set<String> = buildSet {
        if (database) add(SECTION_DATABASE)
        if (settings) add(SECTION_SETTINGS)
        if (credentials) add(SECTION_CREDENTIALS)
        if (appFiles) add(SECTION_APP_FILES)
    }

    companion object {
        const val SECTION_DATABASE = "database"
        const val SECTION_SETTINGS = "settings"
        const val SECTION_CREDENTIALS = "credentials"
        const val SECTION_APP_FILES = "app_files"
    }
}
