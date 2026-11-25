package dev.chungjungsoo.gptmobile.data.voice

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import org.json.JSONObject

/**
 * Binary transport for an already generated native phrase. The first PCM is
 * emitted on arrival; metadata and HTTP headers are never counted as audio.
 */
internal object GatewayVoicePcmStream {
    const val PROTOCOL = "kokoro-pcm-stream-v1"
    const val CONTENT_TYPE = "application/vnd.gptmobile.kokoro-pcm"
    const val PATH = "/gateway/voice/synthesize/stream"
    const val MAX_CHUNK_FRAMES = 4096

    // Deliver the first 40 ms as soon as it arrives, even inside a 4,096-frame wire packet.
    internal const val FIRST_DELIVERY_FRAMES = VOICE_PCM_SAMPLE_RATE / 25
    private const val MAX_HEADER_BYTES = 16_384
    private val magic = byteArrayOf(0x4b, 0x50, 0x43, 0x4d, 1, 0, 0, 0)
    private val sourceWord = Regex("[a-z']+")

    suspend fun read(
        channel: ByteReadChannel,
        id: String,
        model: String,
        tokenCount: Int,
        expectedWords: IntArray,
        text: String,
        emit: (VoicePcmChunk) -> Boolean
    ) {
        try {
            check(exact(channel, magic.size).contentEquals(magic)) { "Invalid Gateway PCM stream" }
            val length = unsignedInt(exact(channel, 4))
            check(length in 1..MAX_HEADER_BYTES.toLong()) { "Invalid Gateway PCM header length" }
            val metadata = JSONObject(exact(channel, length.toInt()).decodeToString(throwOnInvalidSequence = true))
            check(metadata.getString("stream_protocol") == PROTOCOL && metadata.getString("pcm_encoding") == "f32le")
            check(metadata.getString("generation_mode") == "complete_phrase" && integer(metadata, "channels") == 1L)
            val chunkLimit = integer(metadata, "max_chunk_frames")
            check(chunkLimit in 1..MAX_CHUNK_FRAMES.toLong())
            val timing = GatewayVoiceProtocol.timing(metadata, id, model, tokenCount, expectedWords)
            check(integer(metadata, "audio_frames") == timing.frameCount.toLong())
            val cues = cues(text, timing.durations, expectedWords)
            val packetSize = ByteArray(Int.SIZE_BYTES)
            val payload = ByteArray(chunkLimit.toInt() * Float.SIZE_BYTES)
            val floats = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var received = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                readFully(channel, packetSize, packetSize.size)
                val count = unsignedInt(packetSize)
                if (count == 0L) {
                    check(received == timing.frameCount) { "Incomplete Gateway PCM stream" }
                    // A terminator cannot conceal another phrase or extra audio.
                    val tail = ByteArray(1)
                    var extra: Int
                    do {
                        extra = channel.readAvailable(tail, 0, 1)
                        if (extra == 0) yield()
                    } while (extra == 0)
                    check(extra < 0) { "Unexpected Gateway PCM trailer" }
                    break
                }
                check(count <= chunkLimit && count <= timing.frameCount - received) { "Invalid Gateway PCM chunk" }
                var remaining = count.toInt()
                while (remaining > 0) {
                    val frames = if (received == 0) minOf(remaining, FIRST_DELIVERY_FRAMES) else remaining
                    readFully(channel, payload, frames * Float.SIZE_BYTES)
                    val samples = FloatArray(frames)
                    floats.position(0)
                    floats.get(samples)
                    check(samples.all(Float::isFinite)) { "Invalid Gateway PCM samples" }
                    currentCoroutineContext().ensureActive()
                    // Each callback owns its float array. Backpressure stops the next network read.
                    if (!emit(VoicePcmChunk(samples, wordCues = if (received == 0) cues else emptyList()))) {
                        throw CancellationException("Speech cancelled")
                    }
                    received += frames
                    remaining -= frames
                }
            }
        } finally {
            channel.cancel(null)
        }
    }

    private fun cues(text: String, durations: LongArray, words: IntArray): List<VoiceWordCue> {
        // The retained Kokoro frontend accepts normalized ASCII only; byte and
        // character offsets are identical here and map through its source ranges.
        check(text.length <= 128 && text.all { it.code in 0..127 })
        val ranges = sourceWord.findAll(text).map { it.range }.toList()
        check(ranges.size * 2 == words.size)
        val frames = LongArray(durations.size + 1)
        for (i in durations.indices) frames[i + 1] = frames[i] + durations[i] * 600
        return ranges.mapIndexed { index, range ->
            val start = words[index * 2]
            val end = words[index * 2 + 1]
            check(start in 0 until durations.size && end in start + 1..durations.size)
            VoiceWordCue(range.first, range.last + 1, frames[start], frames[end], VoiceWordCueProvenance.NATIVE_DURATION)
        }
    }

    private suspend fun exact(channel: ByteReadChannel, count: Int): ByteArray {
        val bytes = ByteArray(count)
        readFully(channel, bytes, count)
        return bytes
    }

    private suspend fun readFully(channel: ByteReadChannel, bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(bytes, offset, count - offset)
            check(read >= 0) { "Incomplete Gateway PCM stream" }
            if (read == 0) yield() else offset += read
        }
    }

    private fun unsignedInt(bytes: ByteArray): Long =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        check(value is Int || value is Long)
        return (value as Number).toLong()
    }
}
