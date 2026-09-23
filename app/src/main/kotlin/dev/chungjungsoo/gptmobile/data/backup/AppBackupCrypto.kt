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

    /**
     * Version 2 requires a user passphrase for new exports, authenticates the
     * format version as AAD, and raises the PBKDF2 work factor to the current
     * OWASP recommendation for PBKDF2-HMAC-SHA256.
     *
     * Version 1 remains readable so existing backups are not stranded.
     */
    private const val FORMAT_VERSION: Byte = 2
    private const val LEGACY_FORMAT_VERSION: Byte = 1
    private const val PAYLOAD_CONFIG: Byte = 1
    private const val PAYLOAD_DATABASE: Byte = 2
    private const val PAYLOAD_USER_BACKUP: Byte = 3
    private const val PAYLOAD_FAVORITES: Byte = 4

    private const val LEGACY_ITERATIONS = 65_536
    private const val CURRENT_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE_BYTES = 12
    private const val SALT_SIZE_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 128 * 1024 * 1024
    private const val MIN_PASSPHRASE_LENGTH = 8

    /**
     * Kept only to decrypt legacy v1 backups that were exported without a
     * passphrase. New exports never use this embedded fallback.
     */
    private val LEGACY_INTERNAL_SECRET = charArrayOf(
        'g', 'p', 't', '_', 'm', 'o', 'b', 'i', 'l', 'e', '_', 'a', 'p', 'p', '_',
        'k', 'e', 'y', '_', '9', 'e', '2', 'b', '8', 'd', '4', '1', 'c', '7', 'a'
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun deriveKey(
        salt: ByteArray,
        passphrase: String?,
        iterations: Int,
        allowLegacyFallback: Boolean
    ): SecretKeySpec {
        val secretChars = when {
            !passphrase.isNullOrEmpty() -> passphrase.toCharArray()
            allowLegacyFallback -> LEGACY_INTERNAL_SECRET.copyOf()
            else -> throw IllegalArgumentException("A backup passphrase is required.")
        }

        val spec = PBEKeySpec(secretChars, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
            secretChars.fill('\u0000')
        }
    }

    fun encryptConfig(payload: ConfigBackupPayload, outputStream: OutputStream, passphrase: String? = null) {
        encryptSerialized(PAYLOAD_CONFIG, json.encodeToString(payload), outputStream, passphrase)
    }

    fun decryptConfig(inputStream: InputStream, passphrase: String? = null): ConfigBackupPayload =
        decryptSerialized(PAYLOAD_CONFIG, inputStream, passphrase)

    fun encryptDatabase(payload: DatabaseBackupPayload, outputStream: OutputStream, passphrase: String? = null) {
        encryptSerialized(PAYLOAD_DATABASE, json.encodeToString(payload), outputStream, passphrase)
    }

    fun decryptDatabase(inputStream: InputStream, passphrase: String? = null): DatabaseBackupPayload =
        decryptSerialized(PAYLOAD_DATABASE, inputStream, passphrase)

    fun encryptFavorites(payload: FavoritesBackupPayload, outputStream: OutputStream, passphrase: String? = null) {
        encryptSerialized(PAYLOAD_FAVORITES, json.encodeToString(payload), outputStream, passphrase)
    }

    fun decryptFavorites(inputStream: InputStream, passphrase: String? = null): FavoritesBackupPayload =
        decryptSerialized(PAYLOAD_FAVORITES, inputStream, passphrase)

    fun encryptUserBackup(payload: UserBackupData, outputStream: OutputStream, passphrase: String? = null) {
        encryptSerialized(PAYLOAD_USER_BACKUP, json.encodeToString(payload), outputStream, passphrase)
    }

    fun decryptUserBackup(inputStream: InputStream, passphrase: String? = null): UserBackupData =
        decryptSerialized(PAYLOAD_USER_BACKUP, inputStream, passphrase)

    private fun encryptSerialized(
        payloadType: Byte,
        serialized: String,
        outputStream: OutputStream,
        passphrase: String?
    ) {
        requireSecurePassphrase(passphrase)
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        try {
            encryptBytes(payloadType, bytes, outputStream, requireNotNull(passphrase))
        } finally {
            bytes.fill(0)
        }
    }

    private inline fun <reified T> decryptSerialized(
        expectedPayloadType: Byte,
        inputStream: InputStream,
        passphrase: String?
    ): T {
        val (type, plaintext) = decryptBytes(inputStream, passphrase)
        try {
            require(type == expectedPayloadType) { payloadTypeError(expectedPayloadType) }
            return json.decodeFromString(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encryptBytes(
        payloadType: Byte,
        plaintext: ByteArray,
        outputStream: OutputStream,
        passphrase: String
    ) {
        require(payloadType in PAYLOAD_CONFIG..PAYLOAD_FAVORITES) { "Unsupported backup payload type." }

        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE_BYTES).also(random::nextBytes)
        var ciphertext: ByteArray? = null

        try {
            val keySpec = deriveKey(
                salt = salt,
                passphrase = passphrase,
                iterations = CURRENT_ITERATIONS,
                allowLegacyFallback = false
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(byteArrayOf(FORMAT_VERSION, payloadType))
            ciphertext = cipher.doFinal(plaintext)

            outputStream.write(MAGIC)
            outputStream.write(byteArrayOf(FORMAT_VERSION, payloadType))
            outputStream.write(salt)
            outputStream.write(iv)
            outputStream.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ciphertext.size).array())
            outputStream.write(ciphertext)
            outputStream.flush()
        } finally {
            salt.fill(0)
            iv.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun decryptBytes(inputStream: InputStream, passphrase: String?): Pair<Byte, ByteArray> {
        val magic = ByteArray(MAGIC.size)
        val readMagic = inputStream.readNBytes(magic, 0, magic.size)
        if (readMagic != magic.size || !magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a valid encrypted GPT Mobile backup file.")
        }

        val version = inputStream.read()
        if (version != LEGACY_FORMAT_VERSION.toInt() && version != FORMAT_VERSION.toInt()) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        val payloadTypeInt = inputStream.read()
        if (payloadTypeInt !in PAYLOAD_CONFIG.toInt()..PAYLOAD_FAVORITES.toInt()) {
            throw IllegalArgumentException("Unsupported backup payload type.")
        }
        val payloadType = payloadTypeInt.toByte()

        if (version == FORMAT_VERSION.toInt()) {
            requireSecurePassphrase(passphrase)
        }

        val salt = ByteArray(SALT_SIZE_BYTES)
        val iv = ByteArray(IV_SIZE_BYTES)
        val lengthBuffer = ByteArray(Int.SIZE_BYTES)
        var ciphertext: ByteArray? = null

        try {
            if (inputStream.readNBytes(salt, 0, salt.size) != salt.size) {
                throw IllegalArgumentException("Corrupted backup file: missing salt.")
            }
            if (inputStream.readNBytes(iv, 0, iv.size) != iv.size) {
                throw IllegalArgumentException("Corrupted backup file: missing IV.")
            }
            if (inputStream.readNBytes(lengthBuffer, 0, lengthBuffer.size) != lengthBuffer.size) {
                throw IllegalArgumentException("Corrupted backup file: missing length.")
            }

            val ciphertextSize = ByteBuffer.wrap(lengthBuffer).int
            if (ciphertextSize <= 0 || ciphertextSize > MAX_CIPHERTEXT_BYTES) {
                throw IllegalArgumentException("Invalid backup payload size.")
            }

            ciphertext = ByteArray(ciphertextSize)
            var totalRead = 0
            while (totalRead < ciphertextSize) {
                val count = inputStream.read(ciphertext, totalRead, ciphertextSize - totalRead)
                if (count < 0) break
                if (count == 0) continue
                totalRead += count
            }
            if (totalRead != ciphertextSize) {
                throw IllegalArgumentException("Unexpected end of backup file.")
            }

            val isLegacy = version == LEGACY_FORMAT_VERSION.toInt()
            val keySpec = deriveKey(
                salt = salt,
                passphrase = passphrase,
                iterations = if (isLegacy) LEGACY_ITERATIONS else CURRENT_ITERATIONS,
                allowLegacyFallback = isLegacy
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (isLegacy) {
                cipher.updateAAD(byteArrayOf(payloadType))
            } else {
                cipher.updateAAD(byteArrayOf(FORMAT_VERSION, payloadType))
            }
            return payloadType to cipher.doFinal(ciphertext)
        } finally {
            magic.fill(0)
            salt.fill(0)
            iv.fill(0)
            lengthBuffer.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun requireSecurePassphrase(passphrase: String?) {
        require(!passphrase.isNullOrBlank()) { "A backup passphrase is required." }
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
        }
    }

    private fun payloadTypeError(payloadType: Byte): String = when (payloadType) {
        PAYLOAD_CONFIG -> "Selected file is not a GPT Mobile Configuration backup."
        PAYLOAD_DATABASE -> "Selected file is not a GPT Mobile Database backup."
        PAYLOAD_USER_BACKUP -> "Selected file is not a GPT Mobile User Backup."
        PAYLOAD_FAVORITES -> "Selected file is not a GPT Mobile Favorites backup."
        else -> "Selected file has an unsupported GPT Mobile backup type."
    }
}
