package dev.chungjungsoo.gptmobile.data.memory.v2

import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFoundationTest {
    @Test fun `validity includes future expiry and uses exclusive end`() {
        assertTrue(FactValidity(null, 20).contains(10))
        assertTrue(FactValidity(10, 20).contains(10))
        assertFalse(FactValidity(10, 20).contains(20))
        assertFalse(FactValidity(10, 20).overlaps(FactValidity(20, 30)))
    }

    @Test fun `half life is one half and unknown times gain no invented recency`() {
        assertEquals(0.5, memoryHalfLifeWeight(100, 0, 100), 0.000001)
        assertEquals(0.0, memoryHalfLifeWeight(100, null, 100), 0.0)
        assertEquals(1.0, memoryHalfLifeWeight(100, 200, 100), 0.0)
    }

    @Test fun `envelope binds scope identity and revision and uses fresh nonces`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmMemoryCipher(key)
        val context = MemoryCipherContext("fact", "id", 1, "personal")
        val clear = "A personal memory".encodeToByteArray()
        val encrypted = cipher.encrypt(clear, context)
        assertArrayEquals(clear, cipher.decrypt(encrypted, context))
        assertFalse(encrypted.contentEquals(cipher.encrypt(clear, context)))
        listOf(context.copy(scope = "other"), context.copy(id = "other"), context.copy(revision = 2)).forEach { changed ->
            var rejected = false
            try {
                cipher.decrypt(encrypted, changed)
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }
}
