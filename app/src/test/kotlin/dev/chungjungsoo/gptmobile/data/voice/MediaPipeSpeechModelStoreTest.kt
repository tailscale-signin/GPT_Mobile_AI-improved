package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.core.Delegate
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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

    @Test fun localCompiledPackUsesVerifiedBytesAndAnIndependentLease() = runBlocking {
        val compiled = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO_SM8750).copy(downloadBytes = bytes.size.toLong(), sha256 = hash(bytes))
        val store = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, listOf(compiled)) { error("Import must not use the network") }
        store.importCompiledVocoder { ByteArrayInputStream(ByteArray(bytes.size)) }
        assertFalse(store.isReady(compiled.engine))
        store.importCompiledVocoder { ByteArrayInputStream(bytes) }
        assertTrue(store.isReady(compiled.engine))
        store.acquire(compiled.engine, VoiceSpeechBackend.LITERT_KOKORO_SM8750, 1f, "af_heart").use { lease ->
            assertEquals(compiled.fileName, lease.file.name)
            assertTrue(lease.file.readBytes().contentEquals(bytes))
        }
    }

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

    @Test fun firstLaunchAndPlaybackShareTheSameVerifiedKokoroFile() = runBlocking {
        val directory = files.newFolder()
        val kokoro = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO)
            .copy(downloadBytes = bytes.size.toLong(), sha256 = hash(bytes))
        val requests = AtomicInteger()
        val playback = MediaPipeSpeechModelStore(directory, files.newFolder(), scope, listOf(kokoro)) {
            error("Playback must use the first-launch installation")
        }
        val installer = kokoro.localStore(directory) {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    assertEquals(kokoro.downloadUrl, url)
                    requests.incrementAndGet()
                    return LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
                }
                override fun cancel() = Unit
            }
        }
        installer.install(waitForOwner = true)
        assertTrue(playback.isReady(kokoro.engine))
        playback.acquire(kokoro.engine, VoiceSpeechBackend.LITERT_KOKORO_CPU, 1f, kokoro.defaultVoice).use {
            assertEquals(File(installer.modelDirectory(), kokoro.fileName), it.file)
        }
        installer.install(waitForOwner = true)
        assertEquals(1, requests.get())
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
            assertEquals(Delegate.CPU, actual.delegate)
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

    @Test fun compiledModelCacheIsSharedAcrossVoicesButSeparatedByBucket() = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        val first = store.acquire(model.engine, VoiceSpeechBackend.PHONON_CPU, 1f, "freya", 64)
        val otherVoice = store.acquire(model.engine, VoiceSpeechBackend.PHONON_CPU, 1f, "claire", 64)
        val otherBucket = store.acquire(model.engine, VoiceSpeechBackend.PHONON_CPU, 1f, "freya", 128)
        try {
            assertEquals(first.cacheDirectory, otherVoice.cacheDirectory)
            assertNotEquals(first.cacheDirectory, otherBucket.cacheDirectory)
        } finally {
            first.close()
            otherVoice.close()
            otherBucket.close()
        }
    }

    @Test fun voiceChangeKeepsResidentIdentityAndInputModeChangesExperimentIdentity() {
        val store = store { bytes }
        fun factory(voice: String, incremental: Boolean) = MediaPipeVoicePhraseEngineFactory(
            store,
            model.engine,
            VoiceSpeechBackend.PHONON_CPU,
            1f,
            voice,
            incrementalInput = incremental
        )
        val first = factory("freya", true)
        val otherVoice = factory("claire", true)
        val phraseMode = factory("freya", false)
        assertNotEquals(first.identity, otherVoice.identity)
        assertEquals(first.residentIdentity, otherVoice.residentIdentity)
        assertNotEquals(first.identity, phraseMode.identity)
        assertEquals(0, first.effectiveTargetBucket)
    }

    @Test fun kokoroBucketAndDelegateArePassedToThePinnedSdkAndChangeCacheIdentity(): Unit = runBlocking {
        val kokoro = MediaPipeSpeechEngine.KOKORO
        val store = installedKokoro()
        val configurations = mutableListOf<MediaPipeSpeechTaskConfiguration>()
        val inputs = mutableListOf<String>()
        val completeText = "The selected bucket must not truncate this input. ".repeat(40)
        val identities = mutableSetOf<String>()
        for ((backend, delegate) in listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU to Delegate.GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU to Delegate.NPU)) {
            for (bucket in MediaPipeKokoroBucket.entries) {
                val factory = MediaPipeVoicePhraseEngineFactory(store, kokoro, backend, 1f, "af_heart", bucket) { configuration ->
                    configurations += configuration
                    object : MediaPipeSpeechTask {
                        override fun voiceIds() = setOf("af_heart")
                        override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
                            inputs += text
                            var read = false
                            return object : MediaPipeSpeechStream {
                                override fun readNext(): AudioChunk? = if (read) null else AudioChunk(floatArrayOf(.1f)).also { read = true }
                                override fun cancel() = Unit
                                override fun close() = Unit
                            }
                        }
                        override fun cancel() = Unit
                        override fun close() = Unit
                    }
                }
                assertTrue(identities.add(factory.identity))
                factory.create(files.root, "unused", 2).use { engine ->
                    assertEquals("mediapipe-kokoro-${delegate.name.lowercase()}-requested", engine.executionProvider)
                    assertTrue(engine.supportsIncrementalInput)
                    assertEquals(null, engine.executionEvidence)
                    engine.stream(completeText, 0, 1f) { true }
                }
                assertEquals(delegate, configurations.last().delegate)
            }
        }
        assertEquals(listOf(0, 64, 128, 256, 0, 64, 128, 256), configurations.map { it.targetBucket })
        assertEquals(8, configurations.map { it.cacheDirectory }.toSet().size)
        assertEquals(List(8) { completeText }, inputs)
        assertThrows(IllegalArgumentException::class.java) { configurations.first().copy(targetBucket = 63) }
    }

    @Test fun acceleratorStartupFallbackKeepsTheModelVoiceRateAndIncrementalMode(): Unit = runBlocking {
        val store = installedKokoro()
        for ((backend, delegate) in listOf(VoiceSpeechBackend.LITERT_KOKORO_NPU to Delegate.NPU, VoiceSpeechBackend.LITERT_KOKORO_GPU to Delegate.GPU)) {
            val configurations = mutableListOf<MediaPipeSpeechTaskConfiguration>()
            val observed = mutableListOf<VoiceSpeechBackend>()
            var failAccelerator = true
            val factory = MediaPipeVoicePhraseEngineFactory(
                store,
                MediaPipeSpeechEngine.KOKORO,
                backend,
                1.25f,
                "am_adam",
                MediaPipeKokoroBucket.TOKENS_64,
                onPlaybackDelegate = { observed += it }
            ) { config ->
                configurations += config
                if (config.delegate != Delegate.CPU && failAccelerator) throw IllegalStateException("Accelerator initialization failed")
                object : MediaPipeSpeechTask {
                    override fun voiceIds() = setOf("af_heart", "am_adam")
                    override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Incremental input expected")
                    override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream {
                        assertEquals("am_adam", voiceId)
                        val done = CountDownLatch(1)
                        return object : MediaPipeSpeechStream {
                            private var read = false
                            override fun pushText(text: String) = assertEquals("Kokoro", text)
                            override fun finishInput() = done.countDown()
                            override fun readNext(): AudioChunk? {
                                assertTrue(done.await(2, TimeUnit.SECONDS))
                                return if (read) null else AudioChunk(floatArrayOf(.25f)).also { read = true }
                            }
                            override fun cancel() = done.countDown()
                            override fun close() = Unit
                        }
                    }

                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            factory.create(files.root, "", 2).use { engine ->
                assertTrue(engine.supportsIncrementalInput)
                assertEquals("mediapipe-kokoro-cpu-requested", engine.executionProvider)
                val input = listOf("Kokoro").iterator()
                val output = mutableListOf<Float>()
                engine.streamInput({ if (input.hasNext()) input.next() else null }, 0, 1.25f) {
                    output += it.samples.toList()
                    true
                }
                assertEquals(listOf(.25f), output)
                val reused = mutableListOf<VoiceSpeechBackend>()
                val anotherVoice = MediaPipeVoicePhraseEngineFactory(
                    store,
                    MediaPipeSpeechEngine.KOKORO,
                    backend,
                    1.25f,
                    "af_heart",
                    MediaPipeKokoroBucket.TOKENS_64,
                    onPlaybackDelegate = { reused += it }
                ) { error("Voice selection should reuse the open task") }
                assertEquals(factory.residentIdentity, anotherVoice.residentIdentity)
                repeat(2) { anotherVoice.prepareForPlayback(engine) }
                assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU), reused)
            }
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU), observed)
            assertEquals(listOf(delegate, Delegate.CPU), configurations.map { it.delegate })
            assertEquals(1, configurations.map { it.modelFile }.distinct().size)
            assertEquals(2, configurations.map { it.cacheDirectory }.distinct().size)
            assertTrue(configurations.all { it.speakingRate == 1.25f && it.targetBucket == 64 })

            failAccelerator = false
            factory.create(files.root, "", 2).use { engine -> factory.prepareForPlayback(engine) }
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU, backend), observed)
        }
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun npuRequestsStayStrictForBenchmarksAndNeverRecoverCancelledPreparation(): Unit = runBlocking {
        val store = installedKokoro()
        for (cancel in listOf(false, true)) {
            val delegates = mutableListOf<Delegate>()
            val factory = MediaPipeVoicePhraseEngineFactory(
                store,
                MediaPipeSpeechEngine.KOKORO,
                VoiceSpeechBackend.LITERT_KOKORO_NPU,
                1f,
                onPlaybackDelegate = if (cancel) ({ _ -> error("Cancelled preparations must never recover") }) else null
            ) { config ->
                delegates += config.delegate
                if (cancel) throw CancellationException("Cancelled")
                throw IllegalStateException("Cannot initialize NPU")
            }
            assertThrows(if (cancel) CancellationException::class.java else IllegalStateException::class.java) {
                factory.create(files.root, "", 2)
            }
            assertEquals(listOf(Delegate.NPU), delegates)
        }
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun earlyNpuReadFailureReplaysOnlyConsumedInputAndKeepsWarmedVoice(): Unit = runBlocking {
        val store = installedKokoro()
        val routes = mutableListOf<VoiceSpeechBackend>()
        val reads = mutableMapOf<Delegate, MutableList<String>>()
        val openedVoices = mutableListOf<String>()
        val enumerations = AtomicInteger()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_NPU,
            1f,
            "af_heart",
            onPlaybackDelegate = { routes += it }
        ) { config ->
            object : MediaPipeSpeechTask {
                override fun voiceIds(): Set<String> {
                    enumerations.incrementAndGet()
                    return setOf("af_heart", "am_adam")
                }
                override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Expected incremental input")
                override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream {
                    openedVoices += voiceId
                    val finished = CountDownLatch(1)
                    val input = mutableListOf<String>()
                    reads[config.delegate] = input
                    return object : MediaPipeSpeechStream {
                        var read = false
                        override fun pushText(text: String) {
                            input += text
                        }
                        override fun finishInput() = finished.countDown()
                        override fun readNext(): AudioChunk? {
                            assertTrue(finished.await(2, TimeUnit.SECONDS))
                            if (config.delegate == Delegate.NPU) error("Delegate failed on first native read")
                            return if (read) null else AudioChunk(floatArrayOf(.2f)).also { read = true }
                        }
                        override fun cancel() = finished.countDown()
                        override fun close() = Unit
                    }
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        val source = listOf("Play ", "Kokoro.").iterator()
        factory.create(files.root, "", 2).use { engine ->
            factory.prepareForPlayback(engine)
            engine.selectVoice("am_adam")
            var sourceCalls = 0
            val played = mutableListOf<Float>()
            engine.streamInput({
                sourceCalls++
                if (source.hasNext()) source.next() else null
            }, 0, 1f) { chunk ->
                played += chunk.samples.toList()
                true
            }
            assertEquals(3, sourceCalls)
            assertEquals(listOf(.2f), played)
            assertEquals("mediapipe-kokoro-cpu-requested", engine.executionProvider)
        }
        assertEquals(listOf("Play ", "Kokoro."), reads[Delegate.NPU])
        assertEquals(reads[Delegate.NPU], reads[Delegate.CPU])
        assertEquals(listOf("am_adam", "am_adam"), openedVoices)
        assertEquals(2, enumerations.get())
        assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_NPU, VoiceSpeechBackend.LITERT_KOKORO_CPU), routes)
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun acceleratorErrorAfterTheFirstCallbackNeverReplaysPublishedAudio(): Unit = runBlocking {
        val store = installedKokoro()
        val opens = mutableListOf<Pair<Delegate, String>>()
        val routes = mutableListOf<VoiceSpeechBackend>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_GPU,
            1f,
            onPlaybackDelegate = { routes += it }
        ) { config ->
            sealedTask { text ->
                opens += config.delegate to text
                var emitted = false
                return@sealedTask {
                    if (emitted && config.delegate == Delegate.GPU) error("GPU stopped midway")
                    if (emitted) null else AudioChunk(floatArrayOf(.3f)).also { emitted = true }
                }
            }
        }
        factory.create(files.root, "", 2).use { engine ->
            val played = mutableListOf<Float>()
            assertThrows(IllegalStateException::class.java) {
                engine.stream("First.", 0, 1f) {
                    played += it.samples.toList()
                    true
                }
            }
            assertEquals(listOf(.3f), played)
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU), routes)
            engine.resetCancellation()
            engine.stream("Next.", 0, 1f) {
                played += it.samples.toList()
                true
            }
            assertEquals(listOf(.3f, .3f), played)
        }
        assertEquals(listOf(Delegate.GPU to "First.", Delegate.CPU to "Next."), opens)
        assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_CPU), routes)
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun invalidFirstAcceleratorPcmRecoversBeforePlaybackWhileBenchmarksRemainStrict(): Unit = runBlocking {
        val store = installedKokoro()
        for (live in listOf(false, true)) {
            val delegates = mutableListOf<Delegate>()
            val factory = MediaPipeVoicePhraseEngineFactory(
                store,
                MediaPipeSpeechEngine.KOKORO,
                VoiceSpeechBackend.LITERT_KOKORO_GPU,
                1f,
                onPlaybackDelegate = if (live) ({ _ -> }) else null
            ) { config ->
                delegates += config.delegate
                sealedTask {
                    var emitted = false
                    return@sealedTask {
                        val value = if (config.delegate == Delegate.GPU) Float.NaN else .25f
                        if (emitted) null else AudioChunk(floatArrayOf(value)).also { emitted = true }
                    }
                }
            }
            val played = mutableListOf<Float>()
            factory.create(files.root, "", 2).use { engine ->
                val run = {
                    engine.stream("test", 0, 1f) {
                        played += it.samples.toList()
                        true
                    }
                }
                if (live) run() else assertThrows(IllegalArgumentException::class.java) { run() }
            }
            assertEquals(if (live) listOf(.25f) else emptyList(), played)
            assertEquals(if (live) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.GPU), delegates)
        }
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun cancellingDuringLazyCpuRecoveryNeverPresentsOrRetainsTheReplacementTask(): Unit = runBlocking {
        val store = installedKokoro()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val routes = mutableListOf<VoiceSpeechBackend>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_NPU,
            1f,
            onPlaybackDelegate = { routes += it }
        ) { config ->
            if (config.delegate == Delegate.CPU) {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            val delegate = sealedTask { { error("NPU stream cannot start") } }
            object : MediaPipeSpeechTask by delegate {
                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }
        val engine = factory.create(files.root, "", 2)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit<Throwable?> { runCatching { engine.stream("cancel", 0, 1f) { error("Cancelled audio") } }.exceptionOrNull() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            engine.cancel()
            release.countDown()
            assertTrue(work.get(3, TimeUnit.SECONDS) is CancellationException)
            assertEquals(2, closed.get())
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_NPU), routes)
        } finally {
            release.countDown()
            engine.close()
            executor.shutdownNow()
        }
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    private fun sealedTask(read: (String) -> (() -> AudioChunk?)): MediaPipeSpeechTask = object : MediaPipeSpeechTask {
        override fun voiceIds() = setOf("af_heart")
        override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
            val next = read(text)
            return object : MediaPipeSpeechStream {
                override fun readNext(): AudioChunk? = next()
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private suspend fun installedKokoro(): MediaPipeSpeechModelStore {
        val kokoro = model.copy(engine = MediaPipeSpeechEngine.KOKORO, defaultVoice = "af_heart")
        val store = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, listOf(kokoro)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
                override fun cancel() = Unit
            }
        }
        store.install(kokoro.engine)
        return store
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
