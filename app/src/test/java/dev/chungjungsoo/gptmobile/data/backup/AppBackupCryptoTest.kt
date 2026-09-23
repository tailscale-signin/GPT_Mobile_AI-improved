package dev.chungjungsoo.gptmobile.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
                    chatId = 10,
                    content = "Important prompt",
                    platformType = null,
                    isFavorite = true,
                    createdAt = 1000L,
                    group = "Work"
                ),
                FavoriteItemBackupDto(
                    messageId = 42,
                    chatId = 10,
                    content = "Helpful answer",
                    platformType = "openai-gpt4",
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

    @Test
    fun newBackupsRequireAUserPassphrase() {
        val payload = FavoritesBackupPayload(
            version = 1,
            exportedAt = 1L,
            favoriteIds = emptyList(),
            favoriteGroups = emptyList(),
            messageGroups = emptyMap(),
            favoriteMessages = emptyList()
        )

        val error = runCatching {
            AppBackupCrypto.encryptFavorites(payload, ByteArrayOutputStream(), passphrase = null)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("passphrase", ignoreCase = true) == true)
    }

    @Test
    fun v2BackupRejectsWrongPassphrase() {
        val payload = ConfigBackupPayload(version = 2, exportedAt = 123L)
        val output = ByteArrayOutputStream()
        AppBackupCrypto.encryptConfig(payload, output, "correct-password")

        val encrypted = output.toByteArray()
        assertEquals(2, encrypted[7].toInt())

        val error = runCatching {
            AppBackupCrypto.decryptConfig(ByteArrayInputStream(encrypted), "wrong-password")
        }.exceptionOrNull()

        assertNotNull(error)
    }

    @Test
    fun legacyV1BackupWithPassphraseRemainsReadable() {
        val payload = ConfigBackupPayload(version = 2, exportedAt = 456L)
        val serialized = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.encodeToString(ConfigBackupPayload.serializer(), payload)
        val legacy = legacyV1Encrypt(payloadType = 1, plaintext = serialized.encodeToByteArray(), passphrase = "legacy-password")

        val restored = AppBackupCrypto.decryptConfig(ByteArrayInputStream(legacy), "legacy-password")

        assertEquals(payload.version, restored.version)
        assertEquals(payload.exportedAt, restored.exportedAt)
    }

    private fun legacyV1Encrypt(payloadType: Byte, plaintext: ByteArray, passphrase: String): ByteArray {
        val magic = byteArrayOf(0x47, 0x50, 0x54, 0x42, 0x4B, 0x55, 0x50)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 65_536, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(payloadType))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArrayOutputStream().use { out ->
            out.write(magic)
            out.write(byteArrayOf(1, payloadType))
            out.write(salt)
            out.write(iv)
            out.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array())
            out.write(ciphertext)
            out.toByteArray()
        }.also {
            spec.clearPassword()
            keyBytes.fill(0)
            salt.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            plaintext.fill(0)
        }
    }

}
