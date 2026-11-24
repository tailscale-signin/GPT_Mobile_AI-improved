package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaPipeSpeechModelStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val bytes = "Small integrity test fixture, not a speech checkpoint".toByteArray()
    private val model = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.PHONON).copy(
        downloadBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    @After fun cancelScope() = scope.cancel()

    @Test fun refreshAndReadinessNeverDownloadAndInstallUsesThePinnedAsset() = runBlocking {
        val downloads = AtomicInteger()
        val store = store { url ->
            assertEquals(model.downloadUrl, url)
            downloads.incrementAndGet()
            bytes
        }
        store.refresh()
        assertFalse(store.isReady(model.engine))
        assertThrows(IOException::class.java) { acquire(store) }
        assertEquals(0, downloads.get())
        store.install(model.engine)
        assertTrue(store.isReady(model.engine))
        acquire(store).use { assertEquals(model.sha256, hash(it.file.readBytes())) }
        assertEquals(1, downloads.get())
        assertTrue(store.state.value.containsKey(model.engine))
    }

    @Test fun incorrectHashOrLengthCannotPublishANativeLoadableModel() = runBlocking {
        listOf(ByteArray(bytes.size), bytes + byteArrayOf(1), bytes.copyOf(bytes.size - 1)).forEach { bad ->
            val store = store { bad }
            store.install(model.engine)
            assertEquals(LocalVoiceModelStatus.FAILED, store.localStore(model.engine).state.value.status)
            assertThrows(IOException::class.java) { acquire(store) }
            assertFalse(store.localStore(model.engine).modelDirectory().exists())
        }
    }

    @Test fun nativeLeasePreventsRemovalAndSameLengthTamperingInvalidatesIt(): Unit = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        val lease = acquire(store)
        val cache = lease.cacheDirectory
        File(cache, "compiled.cache").writeText("cache")
        val removal = async { store.remove(model.engine) }
        withTimeout(2_000) {
            while (!store.localStore(model.engine).state.value.message.orEmpty().contains("Waiting")) delay(10)
        }
        assertTrue(lease.file.isFile)
        assertTrue(cache.isDirectory)
        assertThrows(IOException::class.java) { acquire(store) }
        lease.close()
        lease.close()
        removal.await()
        assertFalse(lease.file.exists())
        assertFalse(cache.exists())
        store.install(model.engine)
        val file = acquire(store).use { it.file }
        file.writeBytes(ByteArray(bytes.size))
        assertThrows(IOException::class.java) { acquire(store) }
    }

    @Test fun cacheIdentitySeparatesRateAndDelegateAndPrunesOnlyUnusedConfigurations() = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        val active = acquire(store, rate = 1f)
        File(active.cacheDirectory, "active.marker").writeText("active")
        val old = acquire(store, rate = 1.1f).also { it.close() }
        val gpu = acquire(store, backend = VoiceSpeechBackend.LITERT_KOKORO_GPU, rate = 1.1f).also { it.close() }
        assertNotEquals(old.cacheDirectory, gpu.cacheDirectory)
        repeat(6) { acquire(store, rate = 1.2f + it * .1f).close() }
        assertTrue(File(active.cacheDirectory, "active.marker").isFile)
        assertTrue(checkNotNull(active.cacheDirectory.parentFile).listFiles().orEmpty().size <= 3)
        active.close()
        acquire(store, rate = 2f).close()
        assertTrue(checkNotNull(active.cacheDirectory.parentFile).listFiles().orEmpty().size <= 2)
    }

    @Test fun factoryIdentityAndConfigurationIncludeRateVoiceModelAndRequestedDelegate() = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        var configuration: MediaPipeSpeechTaskConfiguration? = null
        val factory = MediaPipeVoicePhraseEngineFactory(store, model.engine, VoiceSpeechBackend.PHONON_CPU, 1.2f) { config ->
            configuration = config
            object : MediaPipeSpeechTask {
                override fun voiceIds() = setOf("freya")
                override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Preparation must not synthesize")
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        val otherRate = MediaPipeVoicePhraseEngineFactory(store, model.engine, VoiceSpeechBackend.PHONON_CPU, 1f)
        assertFalse(factory.requiresKokoroAssets)
        assertTrue(factory.isReady())
        assertNotEquals(factory.identity, otherRate.identity)
        factory.create(files.root, "no-native-lexicon", 99).use { engine ->
            val actual = checkNotNull(configuration)
            assertEquals("mediapipe-phonon-cpu-requested", engine.executionProvider)
            assertTrue(actual.modelFile.isFile)
            assertTrue(actual.cacheDirectory.isDirectory)
            assertEquals(1.2f, actual.speakingRate, 0f)
            assertEquals("freya", actual.voiceId)
            assertFalse(actual.requestGpu)
        }
        withTimeout(2_000) { store.remove(model.engine) }
        assertFalse(store.isReady(model.engine))
    }

    @Test fun failedPreparationDestructorQuarantinesFactoryAndKeepsItsModelLease() = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        val factory = MediaPipeVoicePhraseEngineFactory(store, model.engine, VoiceSpeechBackend.PHONON_CPU, 1f) {
            object : MediaPipeSpeechTask {
                override fun voiceIds(): Set<String> = emptySet()
                override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("No inference")
                override fun cancel() = Unit
                override fun close(): Unit = error("Synthetic native destructor failure")
            }
        }
        assertThrows(IOException::class.java) { factory.create(files.root, "unused", 2) }
        assertTrue(factory.nativeCleanupPending)
        assertThrows(IllegalStateException::class.java) { factory.create(files.root, "unused", 2) }
        val published = File(store.localStore(model.engine).modelDirectory(), model.fileName)
        val removal = async { store.remove(model.engine) }
        try {
            withTimeout(2_000) {
                while (!store.localStore(model.engine).state.value.message.orEmpty().contains("Waiting")) delay(10)
            }
            assertTrue(published.isFile)
            assertFalse(removal.isCompleted)
        } finally {
            removal.cancelAndJoin()
        }
    }

    private fun acquire(store: MediaPipeSpeechModelStore, backend: VoiceSpeechBackend = VoiceSpeechBackend.PHONON_CPU, rate: Float = 1f) =
        store.acquire(model.engine, backend, rate, model.defaultVoice)

    private fun store(contents: (String) -> ByteArray) = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, listOf(model)) {
        object : LocalVoiceAssetSource {
            override fun open(url: String): LocalVoiceAssetStream {
                val data = contents(url)
                return LocalVoiceAssetStream(ByteArrayInputStream(data), data.size.toLong())
            }
            override fun cancel() = Unit
        }
    }

    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
