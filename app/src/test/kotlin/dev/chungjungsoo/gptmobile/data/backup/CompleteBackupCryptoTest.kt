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

    @Test
    fun multipleChunksRoundTrip() {
        val bytes = ByteArray(2 * 1024 * 1024 + 37) { (it % 239).toByte() }
        val encrypted = encrypt(bytes)
        val output = File(temp.root, "decoded")
        CompleteBackupCrypto.decrypt(encrypted.inputStream(), output, password)
        assertArrayEquals(bytes, output.readBytes())
    }

    @Test
    fun wrongPasswordCorruptionTruncationAndAppendedBytesRejectAndDeleteStaging() {
        val encrypted = encrypt(ByteArray(1024 * 1024 + 37) { 42 })
        val corrupt = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for ((bytes, key) in listOf(
            encrypted to "wrong-password",
            corrupt to password,
            encrypted.copyOf(encrypted.size - 1) to password,
            (encrypted + byteArrayOf(0)) to password
        )) {
            val output = File(temp.root, "decoded")
            assertThrows(Exception::class.java) { CompleteBackupCrypto.decrypt(bytes.inputStream(), output, key) }
            assertFalse(output.exists())
        }
    }

    @Test
    fun chunkReorderingAndHeaderModificationAreAuthenticated() {
        val encrypted = encrypt(ByteArray(2 * 1024 * 1024) { (it % 241).toByte() })
        val chunk = 1024 * 1024 + 16
        val reordered = encrypted.copyOfRange(0, 40) + encrypted.copyOfRange(40 + chunk, encrypted.size) + encrypted.copyOfRange(40, 40 + chunk)
        val modifiedHeader = encrypted.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() }
        for (bytes in listOf(reordered, modifiedHeader)) {
            assertThrows(Exception::class.java) { CompleteBackupCrypto.decrypt(bytes.inputStream(), File(temp.root, "decoded"), password) }
        }
    }

    @Test
    fun freeSpaceLimitIsCheckedBeforeWriting() {
        val encrypted = encrypt(ByteArray(1024))
        val output = File(temp.root, "decoded")
        assertThrows(IllegalArgumentException::class.java) { CompleteBackupCrypto.decrypt(encrypted.inputStream(), output, password, maxBytes = 100) }
        assertFalse(output.exists())
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        val source = File(temp.root, "source").apply { writeBytes(bytes) }
        return ByteArrayOutputStream().also { CompleteBackupCrypto.encrypt(source, it, password) }.toByteArray()
    }
}
