package dev.chungjungsoo.gptmobile.data.voice

/** Injectable graph boundary. Production invokes LiteRT; host tests exercise actual tensor assembly. */
internal interface PocketCompiledGraph : AutoCloseable {
    fun run(inputs: List<FloatArray>): FloatArray
}

/**
 * Causal Mimi decoding with a bounded 256-frame window. Completed transformer blocks are retained;
 * only the incomplete block is recomputed. The fixed vocoder graph produces a complete prefix, so
 * only the newly finalized samples are emitted. No whole utterance is held for playback.
 */
internal class PocketCompiledDecoder(
    private val transformer: PocketCompiledGraph,
    private val vocoder: PocketCompiledGraph,
    private val neutral: FloatArray,
    private val checkActive: () -> Unit
) {
    private val features = FloatArray(CHANNELS * VOCODER_POSITIONS)
    private var finalizedFrames = 0
    private var emittedFrames = 0

    init {
        require(neutral.size == LATENT_WIDTH)
    }

    fun decode(latents: List<FloatArray>, emit: (FloatArray) -> Unit) {
        checkActive()
        val count = latents.size
        require(count in emittedFrames..MAX_FRAMES) { "POCKET_DECODER_FRAME_LIMIT" }
        if (count == emittedFrames) return
        require(latents.all { it.size == LATENT_WIDTH }) { "POCKET_LATENT_SHAPE" }

        if (finalizedFrames == 0) {
            val output = block(latents, 0)
            val keep = minOf(BLOCK_FRAMES, count)
            copyFeatures(output, 0, 0, keep)
            if (keep == BLOCK_FRAMES) finalizedFrames = BLOCK_FRAMES
        }
        while (finalizedFrames >= BLOCK_FRAMES && finalizedFrames < count) {
            val output = block(latents, finalizedFrames - HOP_FRAMES)
            val keep = minOf(HOP_FRAMES, count - finalizedFrames)
            copyFeatures(output, HOP_FRAMES, finalizedFrames, keep)
            if (keep < HOP_FRAMES) break
            finalizedFrames += keep
        }
        checkActive()
        val output = vocoder.run(listOf(features))
        checkActive()
        require(output.size == MAX_FRAMES * SAMPLES_PER_FRAME) { "POCKET_VOCODER_SHAPE" }
        val fresh = output.copyOfRange(emittedFrames * SAMPLES_PER_FRAME, count * SAMPLES_PER_FRAME)
        // Preserve the raw numerical result for validation; never hide a broken delegate by clipping.
        emittedFrames = count
        emit(fresh)
    }

    private fun block(latents: List<FloatArray>, start: Int): FloatArray {
        val input = FloatArray((BLOCK_FRAMES + 1) * LATENT_WIDTH)
        (if (start == 0) neutral else latents[start - 1]).copyInto(input)
        repeat(BLOCK_FRAMES) { frame ->
            (latents.getOrNull(start + frame) ?: neutral).copyInto(input, (frame + 1) * LATENT_WIDTH)
        }
        checkActive()
        val result = transformer.run(listOf(input))
        checkActive()
        require(result.size == CHANNELS * BLOCK_POSITIONS) { "POCKET_TRANSFORMER_SHAPE" }
        return result
    }

    private fun copyFeatures(source: FloatArray, sourceFrame: Int, targetFrame: Int, count: Int) {
        repeat(CHANNELS) { channel ->
            val offset = channel * BLOCK_POSITIONS + sourceFrame * UPSAMPLE
            source.copyInto(features, channel * VOCODER_POSITIONS + targetFrame * UPSAMPLE, offset, offset + count * UPSAMPLE)
        }
    }

    companion object {
        const val LATENT_WIDTH = 32
        const val CHANNELS = 512
        const val BLOCK_FRAMES = 64
        const val HOP_FRAMES = 32
        const val UPSAMPLE = 16
        const val MAX_FRAMES = 256
        const val SAMPLES_PER_FRAME = 1_920
        const val BLOCK_POSITIONS = BLOCK_FRAMES * UPSAMPLE
        const val VOCODER_POSITIONS = MAX_FRAMES * UPSAMPLE
    }
}
