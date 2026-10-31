package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.v2.MemoryCipher
import dev.chungjungsoo.gptmobile.data.memory.v2.MemoryCipherContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Rebuildable encrypted vectors. No memory text, queries, or plaintext vectors reach disk. */
internal class MemoryVectorCache(private val directory: File, private val cipher: MemoryCipher, private val modelVersion: String) {
    fun key(id: String, scope: String, fingerprint: String): String = MessageDigest.getInstance("SHA-256")
        .digest(MemoryCipherContext(modelVersion, id, 1, "$scope:$fingerprint").bytes()).joinToString("") { "%02x".format(it) }

    fun read(key: String): FloatArray? {
        val file = File(directory, key)
        if (!file.isFile) return null
        return runCatching {
            require(file.length() == 430L)
            val bytes = cipher.decrypt(file.readBytes(), context(key))
            try {
                require(bytes.size == 400)
                val buffer = ByteBuffer.wrap(bytes)
                FloatArray(100) { buffer.float }.also { require(valid(it)) }
            } finally {
                bytes.fill(0)
            }
        }.getOrElse {
            check(file.delete() || !file.exists()) { "Cannot discard an invalid vector cache entry." }
            null
        }
    }

    fun write(key: String, vector: FloatArray) {
        require(valid(vector))
        check(directory.isDirectory || directory.mkdirs())
        val bytes = ByteBuffer.allocate(400).apply { vector.forEach(::putFloat) }.array()
        val encrypted = try {
            cipher.encrypt(bytes, context(key))
        } finally {
            bytes.fill(0)
        }
        val temporary = File.createTempFile("vector-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(encrypted) }
            val target = File(directory, key).toPath()
            try {
                Files.move(temporary.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun retain(keys: Set<String>) {
        directory.listFiles().orEmpty().filter { it.name !in keys }.forEach { file ->
            check(file.delete() || !file.exists()) { "Cannot remove stale memory vector." }
        }
    }

    fun bound(maximum: Int) {
        require(maximum > 0)
        directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(maximum).forEach { file ->
            check(file.delete() || !file.exists()) { "Cannot bound engineering vector cache." }
        }
    }

    private fun context(key: String) = MemoryCipherContext("vector:$modelVersion", key, 1, "derived")
    private fun valid(vector: FloatArray) = vector.size == 100 && vector.all(Float::isFinite) && vector.any { it != 0f }
}
