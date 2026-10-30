package dev.chungjungsoo.gptmobile.data.memory.v2

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Routing metadata is authenticated together with encrypted content. */
data class MemoryCipherContext(val kind: String, val id: String, val revision: Long, val scope: String) {
    fun bytes(): ByteArray {
        val values = listOf(kind, id, revision.toString(), scope).map { it.encodeToByteArray() }
        return ByteBuffer.allocate(values.sumOf { it.size + 4 }).apply {
            values.forEach {
                putInt(it.size)
                put(it)
            }
        }.array()
    }
}

interface MemoryCipher {
    fun encrypt(plaintext: ByteArray, context: MemoryCipherContext): ByteArray
    fun decrypt(envelope: ByteArray, context: MemoryCipherContext): ByteArray
}

internal class AesGcmMemoryCipher(private val key: SecretKey) : MemoryCipher {
    override fun encrypt(plaintext: ByteArray, context: MemoryCipherContext): ByteArray {
        require(plaintext.size <= MAX_PAYLOAD_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(context.bytes())
        val nonce = cipher.iv
        require(nonce.size == 12)
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(2 + nonce.size + encrypted.size).put(1).put(1).put(nonce).put(encrypted).array()
    }

    override fun decrypt(envelope: ByteArray, context: MemoryCipherContext): ByteArray {
        require(envelope.size in 30..(MAX_PAYLOAD_BYTES + 30)) { "Invalid memory envelope." }
        require(envelope[0] == 1.toByte() && envelope[1] == 1.toByte()) { "Unsupported memory envelope version." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(2, 14)))
        cipher.updateAAD(context.bytes())
        return cipher.doFinal(envelope, 14, envelope.size - 14)
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 1_048_576
    }
}
