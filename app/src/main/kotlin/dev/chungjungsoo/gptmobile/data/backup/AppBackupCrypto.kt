package dev.chungjungsoo.gptmobile.data.backup

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AppBackupCrypto {
    private val MAGIC = byteArrayOf(0x47, 0x50, 0x54, 0x42, 0x4B, 0x55, 0x50) // "GPTBKUP"
    private const val FORMAT_VERSION: Byte = 1
    private const val PAYLOAD_CONFIG: Byte = 1
    private const val PAYLOAD_DATABASE: Byte = 2
    private const val PAYLOAD_USER_BACKUP: Byte = 3

    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE_BYTES = 12
    private const val SALT_SIZE_BYTES = 16

    private val INTERNAL_SECRET = charArrayOf(
        'g', 'p', 't', '_', 'm', 'o', 'b', 'i', 'l', 'e', '_', 'a', 'p', 'p', '_',
        'k', 'e', 'y', '_', '9', 'e', '2', 'b', '8', 'd', '4', '1', 'c', '7', 'a'
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun deriveKey(salt: ByteArray, passphrase: String? = null): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretChars = if (!passphrase.isNullOrEmpty()) passphrase.toCharArray() else INTERNAL_SECRET
        val spec = PBEKeySpec(secretChars, salt, ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptConfig(payload: ConfigBackupPayload, outputStream: OutputStream, passphrase: String? = null) {
        val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        encryptBytes(PAYLOAD_CONFIG, jsonBytes, outputStream, passphrase)
    }

    fun decryptConfig(inputStream: InputStream, passphrase: String? = null): ConfigBackupPayload {
        val (type, plaintext) = decryptBytes(inputStream, passphrase)
        require(type == PAYLOAD_CONFIG) { "Selected file is not a GPT Mobile Configuration backup." }
        return json.decodeFromString(plaintext.decodeToString())
    }

    fun encryptDatabase(payload: DatabaseBackupPayload, outputStream: OutputStream, passphrase: String? = null) {
        val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        encryptBytes(PAYLOAD_DATABASE, jsonBytes, outputStream, passphrase)
    }

    fun decryptDatabase(inputStream: InputStream, passphrase: String? = null): DatabaseBackupPayload {
        val (type, plaintext) = decryptBytes(inputStream, passphrase)
        require(type == PAYLOAD_DATABASE) { "Selected file is not a GPT Mobile Database backup." }
        return json.decodeFromString(plaintext.decodeToString())
    }

    fun encryptUserBackup(payload: UserBackupData, outputStream: OutputStream, passphrase: String? = null) {
        val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        encryptBytes(PAYLOAD_USER_BACKUP, jsonBytes, outputStream, passphrase)
    }

    fun decryptUserBackup(inputStream: InputStream, passphrase: String? = null): UserBackupData {
        val (type, plaintext) = decryptBytes(inputStream, passphrase)
        require(type == PAYLOAD_USER_BACKUP) { "Selected file is not a GPT Mobile User Backup." }
        return json.decodeFromString(plaintext.decodeToString())
    }

    private fun encryptBytes(
        payloadType: Byte,
        plaintext: ByteArray,
        outputStream: OutputStream,
        passphrase: String? = null
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE_BYTES).also(random::nextBytes)

        val keySpec = deriveKey(salt, passphrase)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(byteArrayOf(payloadType))
        val ciphertext = cipher.doFinal(plaintext)

        outputStream.write(MAGIC)
        outputStream.write(byteArrayOf(FORMAT_VERSION, payloadType))
        outputStream.write(salt)
        outputStream.write(iv)
        outputStream.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array())
        outputStream.write(ciphertext)
        outputStream.flush()
    }

    private fun decryptBytes(inputStream: InputStream, passphrase: String? = null): Pair<Byte, ByteArray> {
        val magic = ByteArray(MAGIC.size)
        val readMagic = inputStream.readNBytes(magic, 0, magic.size)
        if (readMagic != magic.size || !magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a valid encrypted GPT Mobile backup file.")
        }

        val version = inputStream.read()
        if (version != FORMAT_VERSION.toInt()) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        val payloadType = inputStream.read().toByte()

        val salt = ByteArray(SALT_SIZE_BYTES)
        if (inputStream.readNBytes(salt, 0, salt.size) != salt.size) {
            throw IllegalArgumentException("Corrupted backup file: missing salt.")
        }

        val iv = ByteArray(IV_SIZE_BYTES)
        if (inputStream.readNBytes(iv, 0, iv.size) != iv.size) {
            throw IllegalArgumentException("Corrupted backup file: missing IV.")
        }

        val lengthBuffer = ByteArray(4)
        if (inputStream.readNBytes(lengthBuffer, 0, 4) != 4) {
            throw IllegalArgumentException("Corrupted backup file: missing length.")
        }
        val ciphertextSize = ByteBuffer.wrap(lengthBuffer).int
        if (ciphertextSize <= 0 || ciphertextSize > 128 * 1024 * 1024) { // 128MB limit
            throw IllegalArgumentException("Invalid payload size: $ciphertextSize")
        }

        val ciphertext = ByteArray(ciphertextSize)
        var totalRead = 0
        while (totalRead < ciphertextSize) {
            val count = inputStream.read(ciphertext, totalRead, ciphertextSize - totalRead)
            if (count < 0) break
            totalRead += count
        }
        if (totalRead != ciphertextSize) {
            throw IllegalArgumentException("Unexpected end of backup file.")
        }

        val keySpec = deriveKey(salt, passphrase)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(byteArrayOf(payloadType))
        val plaintext = cipher.doFinal(ciphertext)

        return Pair(payloadType, plaintext)
    }
}
