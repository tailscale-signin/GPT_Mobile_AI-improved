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

    @Test fun warmupReadsOnlyTheVerifiedLocalBundlePrefixAndReleasesItsLease(): Unit = runBlocking {
        val payload = ByteArray(3 * 1024 * 1024) { 17 }
        val pack = model.copy(downloadBytes = payload.size.toLong(), sha256 = hash(payload))
        val opens = AtomicInteger()
        val store = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, listOf(pack)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    opens.incrementAndGet()
                    return LocalVoiceAssetStream(ByteArrayInputStream(payload), payload.size.toLong())
                }
                override fun cancel() = Unit
            }
        }
        assertEquals(0, store.prefetchInstalled(pack.engine) { true })
        assertEquals(0, opens.get())
        store.install(pack.engine)
        assertEquals(2 * 1024 * 1024, store.prefetchInstalled(pack.engine) { true })
        var checks = 0
        assertEquals(64 * 1024, store.prefetchInstalled(pack.engine) { ++checks <= 2 })
        assertEquals(1, opens.get())
        store.remove(pack.engine)
        assertFalse(store.isReady(pack.engine))
    }

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

    @Test fun acceleratedCachesFollowTheDriverAndInstalledRuntimeWhileCpuCanReuseItsCache(): Unit = runBlocking {
        val models = files.newFolder()
        val cache = files.newFolder()
        fun store(environment: String) = MediaPipeSpeechModelStore(models, cache, scope, listOf(model), acceleratorEnvironment = environment) {
            object : LocalVoiceAssetSource {
                override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
                override fun cancel() = Unit
            }
        }

        val original = store("first-install")
        val updated = store("updated-install")
        original.install(model.engine)
        for (backend in listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU, VoiceSpeechBackend.LITERT_KOKORO_CPU)) {
            acquire(original, backend).use { first ->
                val marker = File(first.cacheDirectory, "compiled-model").also { it.writeText("driver output") }
                acquire(store("first-install"), backend).use { reopened ->
                    assertTrue(reopened.directoryPresentAtEntry)
                    assertEquals(first.cacheDirectory, reopened.cacheDirectory)
                }
                acquire(updated, backend).use { next ->
                    val cpu = backend == VoiceSpeechBackend.LITERT_KOKORO_CPU
                    assertEquals(cpu, next.directoryPresentAtEntry)
                    assertEquals(cpu, next.cacheDirectory == first.cacheDirectory)
                    assertTrue(marker.isFile)
                }
            }
        }
    }

    @Test fun comparisonRetainsThreeDelegateDirectoriesUntilItsOwnerReleasesThem() = runBlocking {
        val store = store { bytes }
        store.install(model.engine)
        val delegates = listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU)
        val parent = store.retainComparisonCaches(model.engine, delegates, 1f, 0).use {
            val first = delegates.map { backend ->
                acquire(store, backend).use { lease ->
                    assertFalse(lease.directoryPresentAtEntry)
                    assertEquals(0, lease.evictionsOnAcquire)
                    lease.cacheDirectory
                }
            }
            delegates.forEach { backend -> acquire(store, backend).use { lease -> assertTrue(lease.directoryPresentAtEntry) } }
            assertTrue(first.all(File::isDirectory))
            checkNotNull(first.first().parentFile)
        }
        assertTrue(parent.listFiles().orEmpty().size <= 2)
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

    @Test fun aDelegateThatFailsDuringVoiceDiscoveryIsClosedBeforeOpeningCpu(): Unit = runBlocking {
        val store = installedKokoro()
        val events = mutableListOf<String>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_NPU,
            1f,
            onPlaybackDelegate = { events += it.name }
        ) { config ->
            events += "open ${config.delegate}"
            val stub = sealedTask { error("No synthesis during preparation") }
            object : MediaPipeSpeechTask by stub {
                override fun voiceIds(): Set<String> {
                    if (config.delegate == Delegate.NPU) throw UnsatisfiedLinkError("vendor driver unavailable")
                    return setOf("af_heart")
                }
                override fun close() {
                    events += "close ${config.delegate}"
                }
            }
        }
        factory.create(files.root, "", 2).close()
        assertEquals(listOf("open NPU", "close NPU", "open CPU", "LITERT_KOKORO_CPU", "close CPU"), events)
        store.remove(MediaPipeSpeechEngine.KOKORO)
    }

    @Test fun failedGpuWarmupPreparesCpuWithoutPublishingAudioOrVoiceEvidence(): Unit = runBlocking {
        val store = installedKokoro()
        val attempts = mutableListOf<Pair<Delegate, String>>()
        val reported = mutableListOf<VoiceSpeechBackend>()
        val validated = mutableListOf<VoiceSpeechBackend>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_GPU,
            1f,
            onPlaybackDelegate = { reported += it },
            onValidatedSynthesis = { _, configured -> validated += configured }
        ) { config ->
            sealedTask { text ->
                attempts += config.delegate to text
                var read = false
                val next: () -> AudioChunk? = {
                    if (!read) {
                        read = true
                        AudioChunk(floatArrayOf(.2f))
                    } else if (config.delegate == Delegate.GPU) {
                        error("Accelerator failed after a silent warmup packet")
                    } else {
                        null
                    }
                }
                next
            }
        }
        factory.create(files.root, "", 2).use { engine ->
            assertTrue(engine.warmFirstInference(0, 1f))
            assertEquals(listOf(Delegate.GPU to VoiceSilentWarmup.PHRASE, Delegate.CPU to VoiceSilentWarmup.PHRASE), attempts)
            assertTrue(validated.isEmpty())
            val heard = mutableListOf<Float>()
            engine.stream("reply", 0, 1f) { heard.addAll(it.samples.toList()) }
            assertEquals(listOf(.2f), heard)
            assertEquals(Delegate.CPU to "reply", attempts.last())
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU), validated)
            assertEquals(listOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_CPU), reported)
        }
    }

    @Test fun prematureNpuEndWhileWaitingForMoreTextReplaysOnlyTheUnheardPrefix(): Unit = runBlocking {
        val store = installedKokoro()
        val waiting = CountDownLatch(1)
        val calls = AtomicInteger()
        val input = mutableMapOf<Delegate, MutableList<String>>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_NPU,
            1f,
            onPlaybackDelegate = {}
        ) { config ->
            object : MediaPipeSpeechTask {
                override fun voiceIds() = setOf("af_heart")
                override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = error("Expected incremental input")
                override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream {
                    val deltas = mutableListOf<String>()
                    input[config.delegate] = deltas
                    val finished = CountDownLatch(1)
                    return object : MediaPipeSpeechStream {
                        var emitted = false
                        override fun pushText(text: String) {
                            deltas += text
                        }
                        override fun finishInput() = finished.countDown()
                        override fun readNext(): AudioChunk? {
                            if (config.delegate == Delegate.NPU) {
                                check(waiting.await(2, TimeUnit.SECONDS))
                                return null
                            }
                            check(finished.await(2, TimeUnit.SECONDS))
                            if (emitted) return null
                            emitted = true
                            return AudioChunk(floatArrayOf(.3f))
                        }
                        override fun cancel() = finished.countDown()
                        override fun close() = Unit
                    }
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        factory.create(files.root, "", 2).use { engine ->
            val heard = mutableListOf<Float>()
            engine.streamInput({
                when (calls.getAndIncrement()) {
                    0 -> "First "
                    1 -> {
                        waiting.countDown()
                        CountDownLatch(1).await(2, TimeUnit.SECONDS)
                        error("Failed accelerator did not retire its text worker")
                    }
                    2 -> "second."
                    else -> null
                }
            }, 0, 1f) { heard.addAll(it.samples.toList()) }
            assertEquals(listOf(.3f), heard)
            assertEquals(listOf("First "), input[Delegate.NPU])
            assertEquals(listOf("First ", "second."), input[Delegate.CPU])
            assertEquals(4, calls.get())
        }
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

    @Test fun clippedFirstPacketRecoversBeforePlaybackAndADelegateBenchmarkKeepsItsSelectedRoute(): Unit = runBlocking {
        val store = installedKokoro()
        val cases = listOf(
            VoiceSpeechBackend.LITERT_KOKORO_GPU to floatArrayOf(.2f, 1.01f),
            VoiceSpeechBackend.LITERT_KOKORO_NPU to floatArrayOf(-1f, -1f, -1f, -1f)
        )
        for ((selected, damaged) in cases) {
            for (live in listOf(false, true)) {
                val opened = mutableListOf<Delegate>()
                val evidence = mutableListOf<VoiceSpeechBackend>()
                val factory = MediaPipeVoicePhraseEngineFactory(
                    store,
                    MediaPipeSpeechEngine.KOKORO,
                    selected,
                    1f,
                    onPlaybackDelegate = if (live) ({ _ -> }) else null,
                    onValidatedSynthesis = { _, backend -> evidence += backend }
                ) { config ->
                    opened += config.delegate
                    sealedTask {
                        val packets = listOf(AudioChunk(if (config.delegate == Delegate.CPU) floatArrayOf(.25f) else damaged)).iterator()
                        return@sealedTask { if (packets.hasNext()) packets.next() else null }
                    }
                }
                val heard = mutableListOf<Float>()
                factory.create(files.root, "", 2).use { speech ->
                    val play = { speech.stream("test", 0, 1f) { heard.addAll(it.samples.toList()) } }
                    if (live) play() else assertTrue(assertThrows(IllegalArgumentException::class.java, play).message.orEmpty().contains("clipped PCM"))
                }
                val delegate = if (selected == VoiceSpeechBackend.LITERT_KOKORO_NPU) Delegate.NPU else Delegate.GPU
                assertEquals(if (live) listOf(delegate, Delegate.CPU) else listOf(delegate), opened)
                assertEquals(if (live) listOf(.25f) else emptyList(), heard)
                assertEquals(if (live) listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU) else emptyList(), evidence)
            }
        }
    }

    @Test fun isolatedFullScaleSamplesPassAndSaturationAcrossPacketsNeverReplaysPublishedAudio(): Unit = runBlocking {
        val store = installedKokoro()
        val opens = mutableListOf<Pair<Delegate, String>>()
        val factory = MediaPipeVoicePhraseEngineFactory(
            store,
            MediaPipeSpeechEngine.KOKORO,
            VoiceSpeechBackend.LITERT_KOKORO_GPU,
            1f,
            onPlaybackDelegate = {}
        ) { config ->
            sealedTask { phrase ->
                opens += config.delegate to phrase
                val sequence = when (phrase) {
                    "Isolated." -> listOf(AudioChunk(floatArrayOf(1f, .25f, -1f)))
                    "Damaged." -> listOf(AudioChunk(floatArrayOf(.25f, 1f, 1f)), AudioChunk(floatArrayOf(1f, 1f, .5f)))
                    else -> listOf(AudioChunk(floatArrayOf(.5f)))
                }.iterator()
                return@sealedTask { if (sequence.hasNext()) sequence.next() else null }
            }
        }
        val heard = mutableListOf<Float>()
        factory.create(files.root, "", 2).use { speech ->
            fun play(phrase: String) = speech.stream(phrase, 0, 1f) { heard.addAll(it.samples.toList()) }
            play("Isolated.")
            assertThrows(IllegalArgumentException::class.java) { play("Damaged.") }
            speech.resetCancellation()
            play("Next.")
        }
        assertEquals(listOf(1f, .25f, -1f, .25f, 1f, 1f, .5f), heard)
        assertEquals(listOf(Delegate.GPU to "Isolated.", Delegate.GPU to "Damaged.", Delegate.CPU to "Next."), opens)
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

    @Test fun specializedGraphsKeepTheirAcceleratorAndFrenchVoiceOnStandardPackRecovery(): Unit = runBlocking {
        val cases = listOf(
            Triple(MediaPipeSpeechEngine.KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED, VoiceSpeechBackend.LITERT_KOKORO_GPU),
            Triple(MediaPipeSpeechEngine.KOKORO_SM8750, VoiceSpeechBackend.LITERT_KOKORO_SM8750, VoiceSpeechBackend.LITERT_KOKORO_NPU)
        )
        for ((engine, selected, expected) in cases) {
            val packs = listOf(MediaPipeSpeechEngine.KOKORO, engine).map {
                MediaPipeSpeechCatalog.model(it).copy(downloadBytes = bytes.size.toLong(), sha256 = hash(bytes))
            }
            val store = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, packs, requireSm8750 = {}) {
                object : LocalVoiceAssetSource {
                    override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
                    override fun cancel() = Unit
                }
            }
            for (pack in packs) store.localStore(pack.engine).install()
            for (missingVoice in listOf(false, true)) {
                val opened = mutableListOf<MediaPipeSpeechTaskConfiguration>()
                val reported = mutableListOf<VoiceSpeechBackend>()
                val spoken = mutableListOf<String>()
                val factory = MediaPipeVoicePhraseEngineFactory(store, engine, selected, 1f, "ff_siwis", onPlaybackDelegate = { reported += it }) { config ->
                    opened += config
                    val specialized = opened.size == 1
                    if (specialized && !missingVoice) throw IOException("Graph unavailable")
                    val delegate = sealedTask {
                        var emitted = false
                        return@sealedTask { if (emitted) null else AudioChunk(floatArrayOf(.25f)).also { emitted = true } }
                    }
                    object : MediaPipeSpeechTask by delegate {
                        override fun voiceIds() = if (specialized) setOf("af_heart") else setOf("af_heart", "ff_siwis")
                        override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
                            spoken += voiceId
                            return delegate.openStream(text, voiceId)
                        }
                    }
                }
                factory.create(files.root, "", 2).use { it.stream("Bonjour.", 0, 1f) { true } }
                assertEquals(2, opened.size)
                assertNotEquals(opened.first().modelFile, opened.last().modelFile)
                val delegate = if (engine == MediaPipeSpeechEngine.KOKORO_GPU) Delegate.GPU else Delegate.NPU
                assertEquals(listOf(delegate, delegate), opened.map { it.delegate })
                assertEquals(listOf(expected), reported)
                assertEquals(listOf("ff_siwis"), spoken)
            }
        }
    }

    @Test fun missingOptimizedPacksUseInstalledFrenchKokoroOnTheSameAcceleratorOnlyDuringPlayback(): Unit = runBlocking {
        for ((engine, selected, delegate) in listOf(
            Triple(MediaPipeSpeechEngine.KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED, Delegate.GPU),
            Triple(MediaPipeSpeechEngine.KOKORO_SM8750, VoiceSpeechBackend.LITERT_KOKORO_SM8750, Delegate.NPU)
        )) {
            val packs = listOf(MediaPipeSpeechEngine.KOKORO, engine).map {
                MediaPipeSpeechCatalog.model(it).copy(downloadBytes = bytes.size.toLong(), sha256 = hash(bytes))
            }
            val downloads = AtomicInteger()
            val store = MediaPipeSpeechModelStore(files.newFolder(), files.newFolder(), scope, packs) {
                object : LocalVoiceAssetSource {
                    override fun open(url: String): LocalVoiceAssetStream {
                        downloads.incrementAndGet()
                        return LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
                    }
                    override fun cancel() = Unit
                }
            }
            val reports = mutableListOf<VoiceSpeechBackend>()
            val strict = MediaPipeVoicePhraseEngineFactory(store, engine, selected, 1f, "ff_siwis") { error("Benchmark cannot open another pack") }
            val playback = MediaPipeVoicePhraseEngineFactory(store, engine, selected, 1f, "ff_siwis", onPlaybackDelegate = { reports += it }) { config ->
                assertEquals(delegate, config.delegate)
                assertEquals(packs.first().fileName, config.modelFile.name)
                val stub = sealedTask {
                    val packets = listOf(AudioChunk(floatArrayOf(.3f))).iterator()
                    return@sealedTask { if (packets.hasNext()) packets.next() else null }
                }
                object : MediaPipeSpeechTask by stub {
                    override fun voiceIds() = setOf("ff_siwis")
                    override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream {
                        assertEquals("ff_siwis", voiceId)
                        return stub.openStream(text, voiceId)
                    }
                }
            }
            assertFalse(playback.isReady())
            store.install(MediaPipeSpeechEngine.KOKORO)
            assertTrue(playback.isReady())
            assertFalse(strict.isReady())
            var prefetched = 0
            playback.prefetchAssets { ++prefetched > 0 }
            assertTrue(prefetched > 1)
            assertThrows(IOException::class.java) { strict.create(files.root, "", 2) }
            playback.create(files.root, "", 2).use { it.stream("Bonjour.", 0, 1f) { true } }
            assertEquals(listOf(if (delegate == Delegate.GPU) VoiceSpeechBackend.LITERT_KOKORO_GPU else VoiceSpeechBackend.LITERT_KOKORO_NPU), reports)
            assertEquals(1, downloads.get())

            // A once-ready optimized pack can also disappear between UI readiness and the lease.
            store.localStore(engine).install()
            assertTrue(strict.isReady())
            assertTrue(File(store.localStore(engine).modelDirectory(), packs.last().fileName).delete())
            playback.create(files.root, "", 2).use { it.stream("Encore.", 0, 1f) { true } }
            assertFalse(strict.isReady())
            store.remove(MediaPipeSpeechEngine.KOKORO)
            assertFalse(playback.isReady())
        }
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
