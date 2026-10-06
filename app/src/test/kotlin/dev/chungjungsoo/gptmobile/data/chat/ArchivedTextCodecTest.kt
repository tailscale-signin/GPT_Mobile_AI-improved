package dev.chungjungsoo.gptmobile.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedTextCodecTest {
    @Test fun largeUnicodeResearchCompressesAndRestoresExactly() {
        val source = "Research 😀 https://example.org/source\n".repeat(10000)
        val archived = ArchivedTextCodec.encode(source)
        assertTrue(archived.length < source.length / 20)
        assertEquals(source, ArchivedTextCodec.decode(archived))
        assertEquals(source, ArchivedTextCodec.decode(source))
    }

    @Test fun smallLegacyAndInvalidPayloadsStayIntact() {
        val text = "Short reply."
        assertSame(text, ArchivedTextCodec.encode(text))
        assertSame(text, ArchivedTextCodec.decode(text))
        val invalid = ArchivedTextCodec.PREFIX + "bad:data"
        assertEquals(invalid, ArchivedTextCodec.decode(invalid))
    }
}
