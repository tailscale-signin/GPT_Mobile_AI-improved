package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KokoroVocoderPatchTest {
    @get:Rule val files = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val standard = MediaPipeSpeechEngine.KOKORO
    private val gpu = MediaPipeSpeechEngine.KOKORO_GPU
    private val npu = MediaPipeSpeechEngine.KOKORO_SM8750
    private val baseBytes = "abcdef".toByteArray()

    @After fun releaseScope() = scope.cancel()

    @Test fun reconstructsNonSequentialCopiesAndLiteralsWithShortOffsetReads() {
        val base = files.newFile().apply { writeBytes(baseBytes) }
        val instructions = patch {
            copyRecord(3, 3)
            literalRecord(byteArrayOf(0, -1))
            copyRecord(0, 2)
            writeByte(0)
        }
        KokoroVocoderPatch.open(base, instructions.inputStream(), 7).use { stream ->
            assertEquals('d'.code, stream.read())
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4) { 42 }
            while (true) {
                val count = stream.read(buffer, 1, 2)
                if (count < 0) break
                assertTrue(count in 1..2)
                assertEquals(42, buffer.first().toInt())
                assertEquals(42, buffer.last().toInt())
                result.write(buffer, 1, count)
            }
            assertArrayEquals(byteArrayOf(101, 102, 0, -1, 97, 98), result.toByteArray())
            assertEquals(0, stream.read(buffer, 0, 0))
            assertEquals(-1, stream.read())
        }
    }

    @Test fun rejectsInvalidBoundsTruncationAndBytesAfterEnd() {
        val base = files.newFile().apply { writeBytes(baseBytes) }
        val end = record(0)
        val copyOne = record(1, 0, 1)
        val malformed = listOf(
            record(1, -1, 1) + end,
            record(1, baseBytes.size, 1) + end,
            record(1, 0, 0) + copyOne + end,
            record(1, 0, -1) + end,
            record(1, 0, 2) + end,
            record(2, -1) + end,
            record(2, 1), // Missing literal
            record(1, 0), // Missing copy length
            end, // Too little output
            copyOne, // Missing end marker
            copyOne + end + byteArrayOf(7),
            record(3) + end
        )
        for (instructions in malformed) {
            assertThrows(IOException::class.java) {
                KokoroVocoderPatch.open(base, patch { write(instructions) }.inputStream(), 1).use { it.readBytes() }
            }
        }
    }

    @Test fun derivesBothVerifiedPacksLocallyAndReleasesTheStandardModel() = runBlocking {
        val opened = mutableListOf<MediaPipeSpeechEngine>()
        val store = newStore(opened)
        store.install(standard)
        for ((engine, backend) in listOf(gpu to VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED, npu to VoiceSpeechBackend.LITERT_KOKORO_SM8750)) {
            store.install(engine)
            assertTrue(store.isReady(engine))
            store.acquire(engine, backend, 1f, "af_heart").use { lease ->
                assertArrayEquals(targetBytes(engine), lease.file.readBytes())
            }
        }
        assertEquals(listOf(gpu, npu), opened)
        store.remove(standard)
        assertFalse(store.isReady(standard))
        assertTrue(store.isReady(gpu))
        assertTrue(store.isReady(npu))
    }

    @Test fun rejectsWrongDeviceAndDamagedPatchWithoutRetainingBaseOrPublishingPartialAudioPack() = runBlocking {
        val opened = mutableListOf<MediaPipeSpeechEngine>()
        val store = newStore(opened, supported = false, damageGpuPatch = true)
        store.install(standard)
        checkRejectedInstall(store, npu)
        assertTrue(opened.isEmpty())
        checkRejectedInstall(store, gpu)
        assertEquals(listOf(gpu), opened)
        store.remove(standard)
        assertFalse(store.isReady(standard))
        assertFalse(store.localStore(standard).modelDirectory().exists())
    }

    private suspend fun checkRejectedInstall(store: MediaPipeSpeechModelStore, engine: MediaPipeSpeechEngine) {
        store.install(engine)
        assertEquals(LocalVoiceModelStatus.FAILED, store.localStore(engine).state.value.status)
        assertFalse(store.localStore(engine).modelDirectory().exists())
    }

    private fun newStore(opened: MutableList<MediaPipeSpeechEngine>, supported: Boolean = true, damageGpuPatch: Boolean = false): MediaPipeSpeechModelStore {
        val base = descriptor(standard, baseBytes)
        val networkReads = AtomicInteger()
        return MediaPipeSpeechModelStore(
            files.newFolder(),
            files.newFolder(),
            scope,
            listOf(base, descriptor(gpu, targetBytes(gpu)), descriptor(npu, targetBytes(npu))),
            requireSm8750 = { check(supported) { "Unsupported device" } },
            openVocoderPatch = { engine ->
                opened += engine
                patch {
                    copyRecord(0, 3)
                    literalRecord(if (damageGpuPatch && engine == gpu) byteArrayOf(0) else byteArrayOf(marker(engine)))
                    writeByte(0)
                }.inputStream()
            }
        ) {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    assertEquals(base.downloadUrl, url)
                    assertEquals(1, networkReads.incrementAndGet())
                    return LocalVoiceAssetStream(ByteArrayInputStream(baseBytes), base.downloadBytes)
                }

                override fun cancel() = Unit
            }
        }
    }

    private fun descriptor(engine: MediaPipeSpeechEngine, bytes: ByteArray) = MediaPipeSpeechCatalog.model(engine).copy(
        downloadBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    private fun targetBytes(engine: MediaPipeSpeechEngine) = baseBytes.copyOfRange(0, 3) + marker(engine)

    private fun marker(engine: MediaPipeSpeechEngine): Byte = when (engine) {
        gpu -> 7
        npu -> 9
        else -> error("Expected a derived model")
    }

    private fun patch(records: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use {
            it.writeInt(0x4b534d31)
            it.records()
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.copyRecord(position: Int, length: Int) {
        writeByte(1)
        writeInt(position)
        writeInt(length)
    }

    private fun DataOutputStream.literalRecord(bytes: ByteArray) {
        writeByte(2)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun record(opcode: Int, vararg fields: Int): ByteArray {
        val buffer = ByteBuffer.allocate(1 + fields.size * Int.SIZE_BYTES)
        buffer.put(opcode.toByte())
        fields.forEach(buffer::putInt)
        return buffer.array()
    }
}
