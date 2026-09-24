package dev.chungjungsoo.gptmobile.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompleteBackupCryptoTest {
    @get:Rule val temp = TemporaryFolder()
    private val password = "test-password"
    private val rawKey = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun legacyPasswordFormatStillRoundTrips() {
        val bytes = ByteArray(2 * 1024 * 1024 + 37) { (it % 239).toByte() }
        val encrypted = encryptLegacy(bytes)
        val output = File(temp.root, "decoded-legacy")
        CompleteBackupCrypto.decrypt(encrypted.inputStream(), output, password)
        assertArrayEquals(bytes, output.readBytes())
    }

    @Test
    fun passwordlessFormatRoundTripsAcrossMultipleChunks() {
        val bytes = ByteArray(2 * 1024 * 1024 + 91) { (it % 251).toByte() }
        val encrypted = encryptPasswordless(bytes)
        val output = File(temp.root, "decoded-v2")
        CompleteBackupCrypto.decryptWithKey(encrypted.inputStream(), output, rawKey)
        assertArrayEquals(bytes, output.readBytes())
    }

    @Test
    fun passwordlessWrongKeyCorruptionTruncationAndAppendAreRejected() {
        val encrypted = encryptPasswordless(ByteArray(1024 * 1024 + 37) { 42 })
        val corrupt = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val wrongKey = rawKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        val cases = listOf(
            encrypted to wrongKey,
            corrupt to rawKey,
            encrypted.copyOf(encrypted.size - 1) to rawKey,
            (encrypted + byteArrayOf(0)) to rawKey
        )
        cases.forEachIndexed { index, (bytes, key) ->
            val output = File(temp.root, "decoded-failure-$index")
            assertThrows(Exception::class.java) {
                CompleteBackupCrypto.decryptWithKey(bytes.inputStream(), output, key)
            }
            assertFalse(output.exists())
        }
    }

    @Test
    fun passwordlessHeaderAndChunkOrderingAreAuthenticated() {
        val encrypted = encryptPasswordless(ByteArray(2 * 1024 * 1024) { (it % 241).toByte() })
        val header = 24
        val chunk = 1024 * 1024 + 16
        val reordered =
            encrypted.copyOfRange(0, header) +
                encrypted.copyOfRange(header + chunk, encrypted.size) +
                encrypted.copyOfRange(header, header + chunk)
        val modifiedHeader = encrypted.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }

        listOf(reordered, modifiedHeader).forEachIndexed { index, bytes ->
            assertThrows(Exception::class.java) {
                CompleteBackupCrypto.decryptWithKey(
                    bytes.inputStream(),
                    File(temp.root, "decoded-auth-$index"),
                    rawKey
                )
            }
        }
    }

    @Test
    fun passwordlessFreeSpaceLimitIsCheckedBeforeWriting() {
        val encrypted = encryptPasswordless(ByteArray(1024))
        val output = File(temp.root, "decoded-limit")
        assertThrows(IllegalArgumentException::class.java) {
            CompleteBackupCrypto.decryptWithKey(
                encrypted.inputStream(),
                output,
                rawKey,
                maxBytes = 100
            )
        }
        assertFalse(output.exists())
    }

    private fun encryptLegacy(bytes: ByteArray): ByteArray {
        val source = File(temp.root, "legacy-source").apply { writeBytes(bytes) }
        return ByteArrayOutputStream().also {
            CompleteBackupCrypto.encrypt(source, it, password)
        }.toByteArray()
    }

    private fun encryptPasswordless(bytes: ByteArray): ByteArray {
        val source = File(temp.root, "v2-source").apply { writeBytes(bytes) }
        return ByteArrayOutputStream().also {
            CompleteBackupCrypto.encryptWithKey(source, it, rawKey)
        }.toByteArray()
    }
}
