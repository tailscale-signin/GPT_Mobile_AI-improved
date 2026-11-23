package dev.chungjungsoo.gptmobile.data.voice

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class GatewayVoicePcmStreamTest {
    @Test fun `first PCM arrives before the remaining transport and keeps native word timing`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val first = CompletableDeferred<VoicePcmChunk>()
        val rest = CompletableDeferred<Unit>()
        val chunks = mutableListOf<VoicePcmChunk>()
        val producer = launch(Dispatchers.Default) {
            // Deliberate packet fragmentation exercises reads across network boundaries.
            for (byte in GatewayPcmFixture.header()) channel.writeFully(byteArrayOf(byte))
            channel.writeFully(GatewayPcmFixture.chunk(600))
            rest.await()
            channel.writeFully(GatewayPcmFixture.chunk(1200))
            channel.writeFully(GatewayPcmFixture.integer(0))
            channel.close()
        }
        val consumer = async(Dispatchers.Default) {
            GatewayPcmFixture.read(channel) {
                chunks += it
                first.complete(it)
                true
            }
        }
        val initial = withTimeout(2_000) { first.await() }
        assertEquals(600, initial.samples.size)
        assertEquals(0.25f, initial.samples[0])
        assertFalse(consumer.isCompleted)
        assertEquals(listOf(VoiceWordCue(0, 5, 600, 1200, VoiceWordCueProvenance.NATIVE_DURATION)), initial.wordCues)
        rest.complete(Unit)
        withTimeout(2_000) {
            consumer.await()
            producer.join()
        }
        assertEquals(1800, chunks.sumOf { it.samples.size })
        assertTrue(chunks[1].wordCues.isEmpty())
    }

    @Test fun `invalid identity timing or header bounds cannot emit PCM`() = runBlocking {
        val invalid = listOf(
            GatewayPcmFixture.header().also { it[0] = 0 },
            GatewayPcmFixture.magic + GatewayPcmFixture.integer(Int.MAX_VALUE),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("request_id", "other")),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("model_sha256", "b".repeat(64))),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("audio_frames", 1801)),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("durations", JSONArray(listOf(1, -1, 3)))),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("channels", 2)),
            GatewayPcmFixture.header(GatewayPcmFixture.metadata().put("max_chunk_frames", 4097))
        )
        for (bytes in invalid) {
            var emitted = false
            val error = runCatching {
                GatewayPcmFixture.read(ByteReadChannel(bytes)) {
                    emitted = true
                    true
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(emitted)
        }
    }

    @Test fun `truncation invalid chunks nonfinite samples and extra trailers fail closed`() = runBlocking {
        val invalid = listOf(
            GatewayPcmFixture.integer(0),
            GatewayPcmFixture.integer(-1),
            GatewayPcmFixture.integer(4097),
            GatewayPcmFixture.integer(1801),
            GatewayPcmFixture.chunk(600).dropLast(1).toByteArray(),
            GatewayPcmFixture.chunk(1800, Float.NaN) + GatewayPcmFixture.integer(0),
            GatewayPcmFixture.chunk(1800) + GatewayPcmFixture.integer(0) + byteArrayOf(1)
        )
        for (body in invalid) {
            val error = runCatching { GatewayPcmFixture.read(ByteReadChannel(GatewayPcmFixture.header() + body)) { true } }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
        }
    }

    @Test fun `a stopped consumer cancels the channel without reading another PCM frame`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val producer = launch {
            channel.writeFully(GatewayPcmFixture.header() + GatewayPcmFixture.chunk(600))
            awaitCancellation()
        }
        try {
            var calls = 0
            val error = withTimeout(2_000) {
                runCatching {
                    GatewayPcmFixture.read(channel) {
                        calls++
                        false
                    }
                }.exceptionOrNull()
            }
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals(1, calls)
            assertTrue(channel.isClosedForRead)
        } finally {
            producer.cancelAndJoin()
        }
    }

    @Test fun `cancellation interrupts a pending network read and closes the channel`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val started = CompletableDeferred<Unit>()
        val consumer = launch {
            started.complete(Unit)
            GatewayPcmFixture.read(channel) { true }
        }
        started.await()
        withTimeout(2_000) { consumer.cancelAndJoin() }
        assertTrue(channel.isClosedForRead)
    }
}

internal object GatewayPcmFixture {
    val magic = byteArrayOf(0x4b, 0x50, 0x43, 0x4d, 1, 0, 0, 0)
    val model = "a".repeat(64)
    fun metadata() = JSONObject().put("protocol", GatewayVoiceProtocol.PROTOCOL).put("frontend_id", GatewayVoiceProtocol.FRONTEND)
        .put("frontend_symbols_sha256", GatewayVoiceProtocol.FRONTEND_SYMBOLS_SHA256).put("frames_per_duration", 600)
        .put("sample_rate", 24_000).put("model_sha256", model).put("execution_provider", "CUDAExecutionProvider").put("executed_cuda_nodes", 2)
        .put("request_id", "turn").put("durations", JSONArray(listOf(1, 1, 1))).put("word_tokens", JSONArray(listOf(1, 2)))
        .put("stream_protocol", GatewayVoicePcmStream.PROTOCOL).put("pcm_encoding", "f32le").put("channels", 1)
        .put("generation_mode", "complete_phrase").put("audio_frames", 1800).put("max_chunk_frames", 4096)
    fun header(metadata: JSONObject = metadata()): ByteArray {
        val bytes = metadata.toString().toByteArray()
        return magic + integer(bytes.size) + bytes
    }
    fun integer(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    fun chunk(count: Int, value: Float = .25f): ByteArray =
        ByteBuffer.allocate(4 + count * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(count)
            repeat(count) { putFloat(value) }
        }.array()
    suspend fun read(channel: ByteReadChannel, emit: (VoicePcmChunk) -> Boolean) =
        GatewayVoicePcmStream.read(channel, "turn", model, 3, intArrayOf(1, 2), "hello", emit)
}
