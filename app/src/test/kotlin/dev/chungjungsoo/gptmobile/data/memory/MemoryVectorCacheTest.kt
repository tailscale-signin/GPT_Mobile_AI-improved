package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.v2.AesGcmMemoryCipher
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryVectorCacheTest {
    @Test fun encryptedVectorsSurviveReopenAndBindModelScopeAndContent() {
        val directory = Files.createTempDirectory("memory-vectors").toFile()
        try {
            val cipher = AesGcmMemoryCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            val cache = MemoryVectorCache(directory, cipher, "model1")
            val key = cache.key("memory", "project:a", "content1")
            val vector = FloatArray(100) { (it + 1) / 100f }
            cache.write(key, vector)
            assertArrayEquals(vector, MemoryVectorCache(directory, cipher, "model1").read(key), 0f)
            assertNull(cache.read(cache.key("memory", "project:b", "content1")))
            assertNull(cache.read(cache.key("memory", "project:a", "content2")))
            assertNull(MemoryVectorCache(directory, cipher, "model2").read(key))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun tamperingIsRejectedAndDeletionRemovesCachedVectors() {
        val directory = Files.createTempDirectory("memory-vectors").toFile()
        try {
            val cache = MemoryVectorCache(directory, AesGcmMemoryCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()), "model1")
            val key = cache.key("memory", "personal", "hash")
            cache.write(key, FloatArray(100) { 0.1f })
            val file = directory.resolve(key)
            file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertNull(cache.read(key))
            assertFalse(file.exists())
            cache.write(key, FloatArray(100) { 0.1f })
            cache.retain(emptySet())
            assertNull(cache.read(key))
        } finally {
            directory.deleteRecursively()
        }
    }
}
