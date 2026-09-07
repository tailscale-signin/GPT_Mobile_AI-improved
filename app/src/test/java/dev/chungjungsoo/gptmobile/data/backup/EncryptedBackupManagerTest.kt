package dev.chungjungsoo.gptmobile.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class EncryptedBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: EncryptedBackupManager
    private val passphrase = "user-secret-password-123".toCharArray()

    @Before
    fun setUp() {
        // Use 1,000 iterations for fast unit testing
        manager = EncryptedBackupManager(iterationCount = 1_000)
    }

    @Test
    fun testConfigurationBackupAndRestoreCycle() {
        val backupFile = File(tempFolder.root, "settings.gptcfg.enc")

        val originalConfig = UserConfigurationBackup(
            themeSettings = ThemeConfiguration(
                themeMode = 2,
                dynamicTheme = false,
                trueBlack = true
            ),
            modelConfigurations = mapOf(
                "openai_model" to "gpt-4o",
                "claude_model" to "claude-3-5-sonnet",
                "temperature" to "0.7"
            ),
            mcpConfigurations = mapOf(
                "github_server" to "{\"url\":\"https://api.github.com\",\"auth\":\"token\"}"
            ),
            generalSettings = mapOf(
                "stream_response" to "true",
                "haptic_feedback" to "true"
            )
        )

        val backupResult = manager.backupConfiguration(originalConfig, backupFile, passphrase)
        assertTrue(backupResult.isSuccess)
        assertTrue(backupFile.exists())
        assertTrue(backupFile.length() > 0)

        // Restore with correct password
        val restoreResult = manager.restoreConfiguration(backupFile, passphrase)
        assertTrue(restoreResult.isSuccess)

        val restored = restoreResult.getOrThrow()
        assertEquals(2, restored.themeSettings.themeMode)
        assertFalse(restored.themeSettings.dynamicTheme)
        assertTrue(restored.themeSettings.trueBlack)
        assertEquals("gpt-4o", restored.modelConfigurations["openai_model"])
        assertEquals("claude-3-5-sonnet", restored.modelConfigurations["claude_model"])
        assertTrue(restored.mcpConfigurations.containsKey("github_server"))
    }

    @Test
    fun testConfigurationRestoreFailsWithWrongPassphrase() {
        val backupFile = File(tempFolder.root, "settings.gptcfg.enc")
        val originalConfig = UserConfigurationBackup()

        manager.backupConfiguration(originalConfig, backupFile, passphrase)

        val restoreResult = manager.restoreConfiguration(backupFile, "wrong-password".toCharArray())
        assertTrue(restoreResult.isFailure)
    }

    @Test
    fun testDatabaseBackupAndRestoreCycle() {
        val sourceDb = File(tempFolder.root, "chat_v2")
        val backupEncrypted = File(tempFolder.root, "chat_v2.gptdb.enc")
        val restoredDb = File(tempFolder.root, "chat_v2_restored")

        // Create a mock SQLite database file with standard header
        val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val dummyPayload = "DUMMY_DATABASE_CONTENT_CONVERSATIONS_AND_FAVOURITES".toByteArray()
        FileOutputStream(sourceDb).use {
            it.write(sqliteHeader)
            it.write(dummyPayload)
        }

        // Backup database
        val backupResult = manager.backupDatabase(sourceDb, backupEncrypted, passphrase)
        assertTrue(backupResult.isSuccess)
        assertTrue(backupEncrypted.exists())

        // Restore database
        val restoreResult = manager.restoreDatabase(backupEncrypted, restoredDb, passphrase)
        assertTrue(restoreResult.isSuccess)
        assertTrue(restoredDb.exists())

        val restoredBytes = restoredDb.readBytes()
        assertArrayEquals(sqliteHeader, restoredBytes.copyOfRange(0, sqliteHeader.size))
        assertEquals(sourceDb.readText(), restoredDb.readText())
    }

    @Test
    fun testDatabaseBackupRejectsNonSqliteFile() {
        val invalidFile = File(tempFolder.root, "invalid.db")
        val backupEncrypted = File(tempFolder.root, "invalid.enc")
        invalidFile.writeText("THIS IS NOT A SQLITE DATABASE")

        val result = manager.backupDatabase(invalidFile, backupEncrypted, passphrase)
        assertTrue(result.isFailure)
    }
}
