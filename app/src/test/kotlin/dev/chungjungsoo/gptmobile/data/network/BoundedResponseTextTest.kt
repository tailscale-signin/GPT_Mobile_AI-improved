package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedResponseTextTest {
    @Test fun preservesUtf8AtLimit() {
        val value = "hello 🌍"
        val bytes = value.toByteArray(Charsets.UTF_8)
        assertEquals(value, BoundedResponseText.read(bytes.inputStream(), bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedBody() {
        BoundedResponseText.read(ByteArray(11).inputStream(), 10)
    }
}
