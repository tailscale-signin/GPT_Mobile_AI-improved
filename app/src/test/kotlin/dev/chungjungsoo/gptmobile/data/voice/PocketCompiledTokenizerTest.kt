package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PocketCompiledTokenizerTest {
    private fun tokenizer() = PocketCompiledTokenizer(File(requireNotNull(javaClass.getResource("/pocket-compiled/tokenizer.model")).toURI()))

    @Test
    fun pinnedOriginalModelMatchesIndependentSentencePieceGoldenIds() {
        // Generated with the official sentencepiece 0.2.1 implementation from the pinned model.
        val fixtures = mapOf(
            "Hello world." to intArrayOf(2994, 578, 263),
            "  two  spaces" to intArrayOf(260, 260, 434, 260, 947, 261),
            "Café déjà vu!" to intArrayOf(1130, 601, 745, 772, 745, 1000, 2112, 1451, 483, 682),
            "emoji 🐈 and 中文" to intArrayOf(1574, 3033, 260, 244, 163, 148, 140, 269, 260, 232, 188, 177, 234, 154, 139),
            "line\nline" to intArrayOf(774, 14, 994),
            "123 + 45 = 168?" to intArrayOf(260, 341, 365, 450, 260, 47, 260, 487, 437, 260, 65, 260, 341, 543, 547, 292)
        )
        val tokenizer = tokenizer()
        fixtures.forEach { (text, ids) ->
            assertArrayEquals(text, ids, tokenizer.encode(text))
            assertEquals(text, tokenizer.decode(ids.toList()))
        }
    }

    @Test
    fun modelAndCompleteManifestHavePinnedDigests() {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val model = requireNotNull(javaClass.getResourceAsStream("/pocket-compiled/tokenizer.model")).use { it.readBytes() }
        assertEquals("d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6", digest(model))
        val manifest = PocketCompiledSpeechCatalog.assets.sortedBy { it.name }.joinToString("") { "${it.name}\t${it.bytes}\t${it.sha256}\n" }
        assertEquals(PocketCompiledSpeechCatalog.MODEL_SHA256, digest(manifest.toByteArray()))
        assertEquals(224_355_715L, PocketCompiledSpeechCatalog.TOTAL_BYTES)
    }

    @Test
    fun truncatedProtobufIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PocketCompiledTokenizer.readTable(byteArrayOf(10, 127, 0)) }
    }

    @Test
    fun halfPrecisionHostMathPreservesZerosSubnormalsAndFiniteExtremes() {
        assertEquals(1f, PocketCompiledSynthesizer.halfToFloat(0x3c00), 0f)
        assertEquals(-2f, PocketCompiledSynthesizer.halfToFloat(0xc000.toShort()), 0f)
        assertEquals(65_504f, PocketCompiledSynthesizer.halfToFloat(0x7bff), 0f)
        assertEquals(Math.scalb(1f, -24), PocketCompiledSynthesizer.halfToFloat(1), 0f)
        assertEquals((-0f).toBits(), PocketCompiledSynthesizer.halfToFloat(0x8000.toShort()).toBits())
        assertEquals("Hello world.", PocketCompiledSynthesizer.prepareText("  hello\n  world "))
    }
}
