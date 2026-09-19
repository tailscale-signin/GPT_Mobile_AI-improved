package dev.chungjungsoo.gptmobile.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppBackupCryptoTest {

    @Test
    fun testFavoritesEncryptionDecryption() {
        val originalPayload = FavoritesBackupPayload(
            version = 1,
            exportedAt = 1234567890L,
            favoriteIds = listOf(1, 42, 100),
            favoriteGroups = listOf("Work", "Personal", "AI Research"),
            messageGroups = mapOf(1 to "Work", 42 to "AI Research"),
            favoriteMessages = listOf(
                FavoriteItemBackupDto(
                    messageId = 1,
                    chatRoomId = 10,
                    content = "Important prompt",
                    role = "user",
                    isFavorite = true,
                    createdAt = 1000L,
                    group = "Work"
                ),
                FavoriteItemBackupDto(
                    messageId = 42,
                    chatRoomId = 10,
                    content = "Helpful answer",
                    role = "assistant",
                    isFavorite = true,
                    createdAt = 2000L,
                    group = "AI Research"
                )
            )
        )

        val outputStream = ByteArrayOutputStream()
        AppBackupCrypto.encryptFavorites(originalPayload, outputStream, "test-secret-passphrase")

        val encryptedBytes = outputStream.toByteArray()
        assertTrue(encryptedBytes.isNotEmpty())

        val inputStream = ByteArrayInputStream(encryptedBytes)
        val decryptedPayload = AppBackupCrypto.decryptFavorites(inputStream, "test-secret-passphrase")

        assertEquals(originalPayload.version, decryptedPayload.version)
        assertEquals(originalPayload.favoriteIds, decryptedPayload.favoriteIds)
        assertEquals(originalPayload.favoriteGroups, decryptedPayload.favoriteGroups)
        assertEquals(originalPayload.messageGroups, decryptedPayload.messageGroups)
        assertEquals(2, decryptedPayload.favoriteMessages.size)
        assertEquals("Important prompt", decryptedPayload.favoriteMessages[0].content)
        assertEquals("Work", decryptedPayload.favoriteMessages[0].group)
    }

    @Test
    fun testConfigEncryptionDecryptionWithUiPreferences() {
        val originalPayload = ConfigBackupPayload(
            version = 2,
            exportedAt = 1234567890L,
            uiPreferences = UiPreferencesBackupDto(
                themeMode = 1,
                dynamicTheme = true,
                debugMode = true,
                localRuntimeBackend = "QUALCOMM_QNN"
            ),
            favoriteGroups = listOf("Pinned"),
            messageGroups = mapOf(5 to "Pinned")
        )

        val outputStream = ByteArrayOutputStream()
        AppBackupCrypto.encryptConfig(originalPayload, outputStream, "config-passphrase")

        val encryptedBytes = outputStream.toByteArray()
        assertTrue(encryptedBytes.isNotEmpty())

        val inputStream = ByteArrayInputStream(encryptedBytes)
        val decryptedPayload = AppBackupCrypto.decryptConfig(inputStream, "config-passphrase")

        assertEquals(2, decryptedPayload.version)
        assertNotNull(decryptedPayload.uiPreferences)
        assertEquals(1, decryptedPayload.uiPreferences?.themeMode)
        assertEquals(true, decryptedPayload.uiPreferences?.dynamicTheme)
        assertEquals(true, decryptedPayload.uiPreferences?.debugMode)
        assertEquals("QUALCOMM_QNN", decryptedPayload.uiPreferences?.localRuntimeBackend)
        assertEquals(listOf("Pinned"), decryptedPayload.favoriteGroups)
    }
}
