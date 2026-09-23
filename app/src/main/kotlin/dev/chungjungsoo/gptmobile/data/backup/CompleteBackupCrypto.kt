package dev.chungjungsoo.gptmobile.data.backup

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Each 1 MiB chunk authenticates its position and the complete header. Decrypt only to staging. */
internal object CompleteBackupCrypto {
    private val magic = "GPTFULL1".toByteArray()
    private const val CHUNK = 1024 * 1024
    private const val HEADER = 40
    private const val MAX_BYTES = 1024L * 1024 * 1024 * 1024

    fun encrypt(source: File, output: OutputStream, password: String) {
        require(password.length >= 8) { "Use a backup password with at least 8 characters." }
        val size = source.length()
        require(size in 1..MAX_BYTES)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(magic).put(salt).put(prefix).putLong(size).array()
        val key = key(password, salt)
        try {
            output.write(header)
            DataInputStream(source.inputStream().buffered()).use { input ->
                var remaining = size
                var index = 0
                while (remaining > 0) {
                    val bytes = ByteArray(minOf(remaining, CHUNK.toLong()).toInt()).also(input::readFully)
                    try {
                        output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, header, index++).doFinal(bytes))
                    } finally {
                        bytes.fill(0)
                    }
                    remaining -= bytes.size
                }
                check(input.read() == -1) { "Backup changed while saving. Please try again." }
            }
            output.flush()
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(input: InputStream, target: File, password: String, maxBytes: Long = MAX_BYTES) {
        require(password.isNotEmpty()) { "Enter the original backup password." }
        val data = DataInputStream(input)
        val header = ByteArray(HEADER).also(data::readFully)
        val buffer = ByteBuffer.wrap(header)
        require(ByteArray(8).also(buffer::get).contentEquals(magic)) { "Select a complete GPT Mobile backup." }
        val salt = ByteArray(16).also(buffer::get)
        val prefix = ByteArray(8).also(buffer::get)
        val size = buffer.long
        require(size in 1..minOf(maxBytes, MAX_BYTES)) { "Invalid backup size or insufficient free space." }
        val key = key(password, salt)
        try {
            target.outputStream().buffered().use { output ->
                var remaining = size
                var index = 0
                while (remaining > 0) {
                    val count = minOf(remaining, CHUNK.toLong()).toInt()
                    val bytes = cipher(Cipher.DECRYPT_MODE, key, prefix, header, index++).doFinal(ByteArray(count + 16).also(data::readFully))
                    try {
                        output.write(bytes)
                    } finally {
                        bytes.fill(0)
                    }
                    remaining -= count
                }
                require(data.read() == -1) { "Unexpected data after the backup." }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            key.fill(0)
        }
    }

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, header: ByteArray, index: Int) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putInt(index).array()))
        updateAAD(header)
        updateAAD(ByteBuffer.allocate(4).putInt(index).array())
    }

    private fun key(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }
}
