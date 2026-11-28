package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Host orchestration adapted from john-rocky/LiteRT-Models (MIT); pinned provenance in the license. */
internal class PocketCompiledSynthesizer(
    private val directory: File,
    private val languageModel: PocketCompiledGraph,
    private val transformer: PocketCompiledGraph,
    private val vocoder: PocketCompiledGraph,
    private val checkActive: () -> Unit,
    private val random: Random = Random()
) : AutoCloseable {
    private val tokenizer = PocketCompiledTokenizer(File(directory, PocketCompiledSpeechCatalog.TOKENIZER))
    private val projection = readFloats("pt_input_linear_f32.bin", WIDTH * LATENT_WIDTH)
    private val beginning = readFloats("pt_bos_input_f32.bin", WIDTH)
    private val neutral = readFloats("pt_neutral_latent_f32.bin", LATENT_WIDTH)
    private val embeddingsFile = RandomAccessFile(File(directory, "pt_embed_f16.bin"), "r")
    private val embeddings = embeddingsFile.channel.map(FileChannel.MapMode.READ_ONLY, 0, embeddingsFile.length()).order(ByteOrder.LITTLE_ENDIAN)
    private val keys = FloatArray(GROUPS * CAPACITY * HEAD_WIDTH)
    private val values = FloatArray(keys.size)
    private val mask = FloatArray(HEADS * (CAPACITY + 1))
    private val cosine = FloatArray(HEAD_WIDTH)
    private val sine = FloatArray(HEAD_WIDTH)
    private val zeroNoise = FloatArray(LATENT_WIDTH)
    private var position = 0
    private var voice = ""
    private var voiceFrames = 0
    private var voiceKeys = FloatArray(0)
    private var voiceValues = FloatArray(0)
    var tokenCount = 0
        private set

    fun synthesize(text: String, voiceId: String, emit: (FloatArray) -> Unit) {
        checkActive()
        loadVoice(voiceId)
        for (part in textParts(text)) {
            checkActive()
            val ids = tokenizer.encode(part)
            tokenCount += ids.size
            resetVoice()
            for (id in ids) step(embedding(id), zeroNoise)
            val tail = if (part.split(' ').size <= 4) 5 else 3
            val estimate = ceil((ids.size / 3.0 + 2.0) * 12.5).toInt()
            val maximum = minOf(estimate, CAPACITY - position - 1, PocketCompiledDecoder.MAX_FRAMES)
            require(maximum > tail) { "POCKET_CONTEXT_LIMIT" }
            val latents = ArrayList<FloatArray>(maximum)
            val decoder = PocketCompiledDecoder(transformer, vocoder, neutral, checkActive)
            var input = beginning
            var endFrame = -1
            var complete = false
            for (frame in 0 until maximum) {
                val noise = FloatArray(LATENT_WIDTH) { (random.nextGaussian() * sqrt(.3)).toFloat() }
                val (latent, endLogit) = step(input, noise)
                if (endLogit > -4f && endFrame < 0) endFrame = frame
                if (endFrame >= 0 && frame >= endFrame + tail) {
                    complete = true
                    break
                }
                latents.add(latent)
                input = project(latent)
                if (latents.size == FIRST_DECODE_FRAMES || latents.size > FIRST_DECODE_FRAMES && (latents.size - FIRST_DECODE_FRAMES) % DECODE_STEP_FRAMES == 0) {
                    decoder.decode(latents, emit)
                }
            }
            checkActive()
            // A generation cap is a truncated result, not a successfully spoken sentence.
            check(complete) { "POCKET_EOS_LIMIT" }
            decoder.decode(latents, emit)
        }
    }

    fun resetMeasurements() {
        tokenCount = 0
    }

    private fun textParts(raw: String): List<String> {
        require(raw.toByteArray(Charsets.UTF_8).size <= VoiceIncrementalStreamLimits.MAX_INPUT_BYTES) { "POCKET_TEXT_LIMIT" }
        val result = ArrayList<String>()
        var current = ""
        fun fits(text: String): Boolean = tokenizer.encode(prepareText(text)).size <= MAX_TEXT_TOKENS
        fun flush() {
            if (current.isNotEmpty()) result.add(prepareText(current))
            current = ""
        }
        for (sentence in prepareText(raw).split(Regex("(?<=[.!?])\\s+"))) {
            val combined = if (current.isEmpty()) sentence else "$current $sentence"
            if (fits(combined)) {
                current = combined
                continue
            }
            flush()
            if (fits(sentence)) {
                current = sentence
                continue
            }
            for (word in sentence.split(' ')) {
                val proposed = if (current.isEmpty()) word else "$current $word"
                if (fits(proposed)) {
                    current = proposed
                } else {
                    require(current.isNotEmpty()) { "POCKET_WORD_TOKEN_LIMIT" }
                    flush()
                    require(fits(word)) { "POCKET_WORD_TOKEN_LIMIT" }
                    current = word
                }
            }
        }
        flush()
        return result
    }

    private fun loadVoice(name: String) {
        require(name in PocketCompiledSpeechCatalog.VOICES) { "POCKET_VOICE_UNAVAILABLE" }
        if (voice == name) return
        val bytes = File(directory, "pt_voice_$name.bin").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = buffer.int
        require(frames in 1 until CAPACITY && bytes.size.toLong() == 4L + 4L * GROUPS * frames * HEAD_WIDTH) { "POCKET_VOICE_SHAPE" }
        val size = GROUPS * frames * HEAD_WIDTH
        voiceKeys = FloatArray(size) { halfToFloat(buffer.short) }
        voiceValues = FloatArray(size) { halfToFloat(buffer.short) }
        voiceFrames = frames
        voice = name
    }

    private fun resetVoice() {
        keys.fill(0f)
        values.fill(0f)
        repeat(GROUPS) { group ->
            val source = group * voiceFrames * HEAD_WIDTH
            val target = group * CAPACITY * HEAD_WIDTH
            voiceKeys.copyInto(keys, target, source, source + voiceFrames * HEAD_WIDTH)
            voiceValues.copyInto(values, target, source, source + voiceFrames * HEAD_WIDTH)
        }
        mask.fill(-10_000f)
        repeat(HEADS) { head ->
            val offset = head * (CAPACITY + 1)
            repeat(voiceFrames) { mask[offset + it] = 0f }
            mask[offset + CAPACITY] = 0f
        }
        position = voiceFrames
    }

    private fun embedding(id: Int): FloatArray {
        require(id in 0 until 4_000) { "POCKET_TOKEN_ID" }
        return FloatArray(WIDTH) { halfToFloat(embeddings.getShort((id * WIDTH + it) * 2)) }
    }

    private fun project(latent: FloatArray) = FloatArray(WIDTH) { row ->
        var sum = 0f
        repeat(LATENT_WIDTH) { sum += projection[row * LATENT_WIDTH + it] * latent[it] }
        sum
    }

    private fun step(embedding: FloatArray, noise: FloatArray): Pair<FloatArray, Float> {
        checkActive()
        require(position < CAPACITY) { "POCKET_CONTEXT_LIMIT" }
        repeat(HEAD_WIDTH / 2) { index ->
            val angle = position / 10_000.0.pow(index / 32.0)
            cosine[index] = cos(angle).toFloat()
            cosine[index + HEAD_WIDTH / 2] = cosine[index]
            sine[index] = sin(angle).toFloat()
            sine[index + HEAD_WIDTH / 2] = sine[index]
        }
        val output = languageModel.run(listOf(embedding, cosine, sine, mask, keys, values, noise))
        checkActive()
        require(output.size == 1 + LATENT_WIDTH + 2 * GROUPS * HEAD_WIDTH && output.all(Float::isFinite)) { "POCKET_LM_OUTPUT_INVALID" }
        val base = 1 + LATENT_WIDTH
        repeat(GROUPS) { group ->
            val target = group * CAPACITY * HEAD_WIDTH + position * HEAD_WIDTH
            val key = base + group * HEAD_WIDTH
            val value = base + (GROUPS + group) * HEAD_WIDTH
            output.copyInto(keys, target, key, key + HEAD_WIDTH)
            output.copyInto(values, target, value, value + HEAD_WIDTH)
        }
        repeat(HEADS) { mask[it * (CAPACITY + 1) + position] = 0f }
        position++
        return output.copyOfRange(1, base) to output[0]
    }

    override fun close() = embeddingsFile.close()

    private fun readFloats(name: String, count: Int): FloatArray {
        val bytes = File(directory, name).readBytes()
        require(bytes.size == count * Float.SIZE_BYTES) { "POCKET_HOST_ASSET_SHAPE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }.also { require(it.all(Float::isFinite)) { "POCKET_HOST_ASSET_INVALID" } }
    }

    companion object {
        private const val WIDTH = 1_024
        private const val HEAD_WIDTH = 64
        private const val HEADS = 16
        private const val GROUPS = 96
        private const val CAPACITY = 512
        private const val LATENT_WIDTH = 32
        private const val MAX_TEXT_TOKENS = 50
        private const val FIRST_DECODE_FRAMES = 4
        private const val DECODE_STEP_FRAMES = 16

        internal fun prepareText(raw: String): String {
            var text = raw.trim().replace(Regex("\\s+"), " ")
            require(text.isNotEmpty()) { "POCKET_TEXT_EMPTY" }
            if (!text.first().isUpperCase()) text = text.first().uppercaseChar() + text.drop(1)
            if (text.last().isLetterOrDigit()) text += "."
            return text
        }

        internal fun halfToFloat(bits: Short): Float {
            val half = bits.toInt() and 65_535
            val sign = (half and 32_768) shl 16
            var exponent = (half ushr 10) and 31
            var mantissa = half and 1_023
            if (exponent == 0) {
                if (mantissa == 0) return Float.fromBits(sign)
                exponent = 1
                while (mantissa and 1_024 == 0) {
                    mantissa = mantissa shl 1
                    exponent--
                }
                mantissa = mantissa and 1_023
            } else if (exponent == 31) {
                return Float.fromBits(sign or 0x7f800000 or (mantissa shl 13))
            }
            return Float.fromBits(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
        }
    }
}
