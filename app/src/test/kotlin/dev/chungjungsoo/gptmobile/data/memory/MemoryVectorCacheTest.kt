package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.v2.AesGcmMemoryCipher
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test fun encryptedSourceRangesReuseOnlyMatchingScopeBlobRangeContentAndEncoder() {
        val directory = Files.createTempDirectory("source-ranges").toFile()
        try {
            val cipher = AesGcmMemoryCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            val path = "src/Parser.kt"
            val original = "$path\nblob_sha=${"a".repeat(40)} lines=3-4\nfun parse() = true"
            var embeddings = 0
            fun lookup(scope: String = "github:source-ranges:repo1", record: String = original, model: String = "encoder1"): FloatArray =
                MemoryVectorCache(directory, cipher, model).getOrEmbed(path, scope, record) {
                    embeddings++
                    FloatArray(100) { embeddings.toFloat() }
                }
            val first = lookup()
            assertArrayEquals(first, lookup(), 0f)
            assertEquals(1, embeddings)
            lookup(scope = "github:source-ranges:repo2")
            lookup(scope = "github:engineering-memory")
            lookup(record = original.replace("a".repeat(40), "b".repeat(40)))
            lookup(record = original.replace("lines=3-4", "lines=7-8"))
            lookup(record = original.replace("true", "false"))
            lookup(model = "encoder2")
            assertEquals(7, embeddings)
            assertArrayEquals(first, lookup(), 0f)
            assertEquals(7, directory.listFiles().orEmpty().size)
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
