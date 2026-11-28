package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PocketCompiledDecoderTest {
    private class Transformer : PocketCompiledGraph {
        var calls = 0
        override fun run(inputs: List<FloatArray>): FloatArray {
            calls++
            val latent = inputs.single()
            // A causal fake with previous-frame dependence catches incorrect overlap/context slots.
            return FloatArray(PocketCompiledDecoder.CHANNELS * PocketCompiledDecoder.BLOCK_POSITIONS) { index ->
                val position = index % PocketCompiledDecoder.BLOCK_POSITIONS
                val frame = position / PocketCompiledDecoder.UPSAMPLE
                latent[(frame + 1) * 32] + latent[frame * 32] / 10f
            }
        }
        override fun close() = Unit
    }

    private class Vocoder : PocketCompiledGraph {
        var calls = 0
        override fun run(inputs: List<FloatArray>): FloatArray {
            calls++
            val features = inputs.single()
            return FloatArray(PocketCompiledDecoder.MAX_FRAMES * PocketCompiledDecoder.SAMPLES_PER_FRAME) { index ->
                features[index / PocketCompiledDecoder.SAMPLES_PER_FRAME * PocketCompiledDecoder.UPSAMPLE]
            }
        }
        override fun close() = Unit
    }

    @Test
    fun incrementalPrefixesMatchOneShotAtEveryBlockBoundaryWithoutRepeatedAudio() {
        val latent = List(256) { index -> FloatArray(32) { (index + 1) / 1_000f } }
        val transformer = Transformer()
        val incremental = PocketCompiledDecoder(transformer, Vocoder(), FloatArray(32)) {}
        val output = ArrayList<Float>()
        for (count in listOf(4, 20, 52, 63, 64, 68, 95, 96, 100, 128, 159, 160, 192, 224, 255, 256)) {
            incremental.decode(latent.take(count)) { output.addAll(it.toList()) }
            assertEquals(count * 1_920, output.size)
        }
        var complete = FloatArray(0)
        PocketCompiledDecoder(Transformer(), Vocoder(), FloatArray(32)) {}.decode(latent) { complete = it }
        assertArrayEquals(complete, output.toFloatArray(), 0f)
        for (frame in latent.indices) {
            val expected = (frame + 1) / 1_000f + if (frame == 0) 0f else frame / 10_000f
            assertEquals(expected, complete[frame * 1_920], 1e-7f)
        }
        // Full prefixes would re-run every earlier block at each callback.
        assertEquals(16, transformer.calls)
    }

    @Test
    fun repeatedPrefixEmitsNothingAndDoesNotInvokeGraphs() {
        val transformer = Transformer()
        val vocoder = Vocoder()
        val decoder = PocketCompiledDecoder(transformer, vocoder, FloatArray(32)) {}
        val latent = List(4) { FloatArray(32) }
        var samples = 0
        repeat(2) { decoder.decode(latent) { samples += it.size } }
        assertEquals(7_680, samples)
        assertEquals(1, transformer.calls)
        assertEquals(1, vocoder.calls)
    }

    @Test
    fun cancellationAfterNativeReturnSuppressesThePacket() {
        var cancelled = false
        val transformer = object : PocketCompiledGraph {
            override fun run(inputs: List<FloatArray>): FloatArray {
                cancelled = true
                return FloatArray(512 * 1_024)
            }
            override fun close() = Unit
        }
        val vocoder = Vocoder()
        val decoder = PocketCompiledDecoder(transformer, vocoder, FloatArray(32)) {
            if (cancelled) throw CancellationException()
        }
        var emitted = false
        assertThrows(CancellationException::class.java) { decoder.decode(List(4) { FloatArray(32) }) { emitted = true } }
        assertEquals(false, emitted)
        assertEquals(0, vocoder.calls)
    }

    @Test
    fun wrongTensorShapeAndNonMonotonicPrefixesFailBeforeAudio() {
        val bad = object : PocketCompiledGraph {
            override fun run(inputs: List<FloatArray>) = FloatArray(3)
            override fun close() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PocketCompiledDecoder(bad, Vocoder(), FloatArray(32)) {}.decode(List(4) { FloatArray(32) }) { error("Must not emit") }
        }
        val decoder = PocketCompiledDecoder(Transformer(), Vocoder(), FloatArray(32)) {}
        decoder.decode(List(4) { FloatArray(32) }) {}
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(List(3) { FloatArray(32) }) {} }
    }
}
