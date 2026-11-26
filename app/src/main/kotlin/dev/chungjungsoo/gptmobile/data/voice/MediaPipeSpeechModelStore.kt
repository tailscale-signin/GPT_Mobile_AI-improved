package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Reuses the voice installer's digest checks, resumable HTTPS transfers, atomic publication and
 * native-use leases. Construction, refresh and inference never initiate a download.
 */
internal class MediaPipeSpeechModelStore internal constructor(
    voiceModelsDirectory: File,
    private val cacheRoot: File,
    scope: CoroutineScope,
    private val manifests: List<MediaPipeSpeechModel> = MediaPipeSpeechCatalog.models,
    internal val prepareNpu: () -> Unit = {},
    internal val requireSm8750: () -> Unit = { check(isSm8750SpeechDevice()) { "This vocoder is compiled for Snapdragon 8 Elite (SM8750)." } },
    private val openVocoderPatch: (MediaPipeSpeechEngine) -> InputStream = { throw IOException("Kokoro vocoder patch is unavailable.") },
    sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() }
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        File(context.applicationContext.noBackupFilesDir, "voice-models"),
        File(context.applicationContext.cacheDir, "mediapipe-speech"),
        scope,
        prepareNpu = { prepareMediaPipeSpeechNpu(context.applicationContext) },
        openVocoderPatch = { engine ->
            val file = when (engine) {
                MediaPipeSpeechEngine.KOKORO_SM8750 -> "kokoro_sm8750.ksm"
                MediaPipeSpeechEngine.KOKORO_GPU -> "kokoro_gpu.ksm"
                else -> error("No vocoder patch for ${engine.name}.")
            }
            context.applicationContext.assets.open("voice/$file")
        }
    )

    init {
        require(manifests.isNotEmpty() && manifests.map { it.engine }.distinct().size == manifests.size)
    }

    private val stores = manifests.associate { model ->
        model.engine to model.localStore(voiceModelsDirectory, sourceFactory)
    }

    val state: StateFlow<Map<MediaPipeSpeechEngine, LocalVoiceModelState>> = combine(stores.values.map { it.state }) { values ->
        stores.keys.zip(values.toList()).toMap()
    }.stateIn(scope, SharingStarted.Eagerly, stores.mapValues { it.value.state.value })

    fun localStore(engine: MediaPipeSpeechEngine): LocalVoiceModelStore = stores.getValue(engine)

    suspend fun refresh(engine: MediaPipeSpeechEngine? = null, forceVerification: Boolean = true) {
        if (engine == null) stores.values.forEach { it.refresh(forceVerification) } else localStore(engine).refresh(forceVerification)
    }

    suspend fun install(engine: MediaPipeSpeechEngine) {
        if (engine != MediaPipeSpeechEngine.KOKORO_SM8750 && engine != MediaPipeSpeechEngine.KOKORO_GPU) {
            localStore(engine).install()
            return
        }
        withContext(Dispatchers.IO) {
            val standard = stores[MediaPipeSpeechEngine.KOKORO]
            val leased = standard?.acquireUse() == true
            try {
                if (!leased && engine == MediaPipeSpeechEngine.KOKORO_GPU) {
                    localStore(engine).install()
                } else {
                    installPackFromStream(engine) {
                        if (engine == MediaPipeSpeechEngine.KOKORO_SM8750) requireSm8750()
                        if (!leased) throw IOException("Install standard Kokoro before preparing its Snapdragon vocoder.")
                        val base = manifests.single { it.engine == MediaPipeSpeechEngine.KOKORO }
                        val target = manifests.single { it.engine == engine }
                        KokoroVocoderPatch.open(File(requireNotNull(standard).modelDirectory(), base.fileName), openVocoderPatch(engine), target.downloadBytes)
                    }
                }
            } finally {
                if (leased) standard?.releaseUse()
            }
        }
    }

    suspend fun importCompiledVocoder(openPack: () -> InputStream) =
        installPackFromStream(MediaPipeSpeechEngine.KOKORO_SM8750, openPack)

    private suspend fun installPackFromStream(engine: MediaPipeSpeechEngine, openPack: () -> InputStream) {
        val model = manifests.single { it.engine == engine }
        localStore(model.engine).install(waitForOwner = false, sourceOverride = {
            object : LocalVoiceAssetSource {
                private val input = java.util.concurrent.atomic.AtomicReference<InputStream?>()
                override fun open(url: String): LocalVoiceAssetStream {
                    val stream = openPack()
                    input.set(stream)
                    return LocalVoiceAssetStream(stream, model.downloadBytes)
                }
                override fun cancel() {
                    input.getAndSet(null)?.close()
                }
            }
        })
    }

    suspend fun remove(engine: MediaPipeSpeechEngine) = withContext(Dispatchers.IO) {
        localStore(engine).remove()
        synchronized(cacheUsers) {
            val directory = modelCacheDirectory(engine)
            // Recheck after taking the cache lock: a new install may have started after removal.
            if (localStore(engine).state.value.status == LocalVoiceModelStatus.NOT_INSTALLED &&
                cacheUsers.keys.none { it.startsWith(directory.absolutePath + File.separator) }
            ) {
                directory.deleteRecursively()
            }
        }
    }

    fun cancelInstall(engine: MediaPipeSpeechEngine) = localStore(engine).cancelInstall()

    internal fun isReady(engine: MediaPipeSpeechEngine): Boolean = localStore(engine).isInstalled()

    private fun cacheDirectory(parent: File, backend: VoiceSpeechBackend, speakingRate: Float, bucket: Int) =
        File(parent, "shared-${backend.name.lowercase()}-${speakingRate.toBits().toUInt().toString(16)}-bucket-$bucket")

    /** Keep only the selected keys across separated passes. Active SDK leases remain independent. */
    fun retainComparisonCaches(engine: MediaPipeSpeechEngine, backends: List<VoiceSpeechBackend>, rate: Float, bucket: Int): AutoCloseable {
        require(backends.size in 1..3 && backends.distinct().size == backends.size)
        val parent = modelCacheDirectory(engine)
        val paths = backends.map { cacheDirectory(parent, it, rate, bucket).absolutePath }
        synchronized(cacheUsers) { paths.forEach { pinned[it] = (pinned[it] ?: 0) + 1 } }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(cacheUsers) {
                    paths.forEach { path ->
                        val remaining = pinned.getValue(path) - 1
                        if (remaining == 0) pinned.remove(path) else pinned[path] = remaining
                    }
                    unusedDirectories(parent).drop(2).forEach { it.deleteRecursively() }
                }
            }
        }
    }

    /** SDK disk writes cannot be capped while a native handle owns the files. Bound retained idle comparison caches. */
    private fun trimComparisonCaches(parent: File): Int {
        val idle = parent.listFiles().orEmpty().filter { it.absolutePath in pinned && it.absolutePath !in cacheUsers }
            .sortedByDescending(File::lastModified)
        var remaining = VoiceSpeechPlaybackProtocol.COMPARISON_CACHE_BYTES
        return idle.count { directory ->
            var measured = 0L
            for (file in directory.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
                if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) measured += file.length().coerceAtMost(VoiceSpeechPlaybackProtocol.COMPARISON_CACHE_BYTES + 1)
                if (measured > remaining) break
            }
            if (measured <= remaining) {
                remaining -= measured
                false
            } else {
                directory.deleteRecursively()
            }
        }
    }

    private fun unusedDirectories(parent: File): List<File> = parent.listFiles().orEmpty()
        .filter { it.isDirectory && it.absolutePath !in cacheUsers && it.absolutePath !in pinned }
        .sortedByDescending(File::lastModified)

    /** No network access; a verified file lease remains held until the SDK handle is destroyed. */
    internal fun acquire(engine: MediaPipeSpeechEngine, backend: VoiceSpeechBackend, speakingRate: Float, voiceId: String, targetBucket: Int = 0): ModelLease {
        require(voiceId.isNotBlank())
        MediaPipeKokoroBucket.fromSdkValue(targetBucket)
        val model = manifests.single { it.engine == engine }
        val store = localStore(engine)
        if (!store.acquireUse()) throw IOException("Install or repair ${model.title} in Voice settings.")
        try {
            val parent = modelCacheDirectory(engine)
            // Voice embeddings are selected per utterance. Task initialization uses the
            // bundle default, so voices share the same compiled weights and resident task.
            val directory = cacheDirectory(parent, backend, speakingRate, targetBucket)
            val (present, evicted) = synchronized(cacheUsers) {
                // Cache keys include every exposed SDK configuration input. Keep the current
                // directory and one previous unused configuration; never remove an active lease.
                val present = directory.isDirectory
                val comparisonEvictions = if (pinned.isEmpty()) 0 else trimComparisonCaches(parent)
                val expired = unusedDirectories(parent).filter { it != directory }.drop(1)
                expired.forEach {
                    if (!it.deleteRecursively()) throw IOException("Could not clear an old speech engine cache.")
                }
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not prepare the speech engine cache.")
                directory.setLastModified(System.currentTimeMillis())
                cacheUsers[directory.absolutePath] = (cacheUsers[directory.absolutePath] ?: 0) + 1
                present to expired.size + comparisonEvictions
            }
            return ModelLease(File(store.modelDirectory(), model.fileName), directory, present, evicted) {
                synchronized(cacheUsers) {
                    val count = cacheUsers.getValue(directory.absolutePath)
                    if (count == 1) cacheUsers.remove(directory.absolutePath) else cacheUsers[directory.absolutePath] = count - 1
                    // Concurrent preparations can temporarily keep extra leased directories.
                    // Once they retire, retain at most two unused configurations as well.
                    unusedDirectories(parent).drop(2).forEach { it.deleteRecursively() }
                    if (pinned.isNotEmpty()) trimComparisonCaches(parent)
                }
                store.releaseUse()
            }
        } catch (failure: Throwable) {
            store.releaseUse()
            throw failure
        }
    }

    private fun modelCacheDirectory(engine: MediaPipeSpeechEngine): File {
        val model = manifests.single { it.engine == engine }
        return File(cacheRoot, "${MediaPipeSpeechCatalog.RUNTIME_VERSION}/${model.id}/${model.sha256}")
    }

    internal class ModelLease(val file: File, val cacheDirectory: File, val directoryPresentAtEntry: Boolean, val evictionsOnAcquire: Int, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private companion object {
        val cacheUsers = mutableMapOf<String, Int>()
        val pinned = mutableMapOf<String, Int>()
    }
}

internal fun MediaPipeSpeechModel.localStore(
    voiceModelsDirectory: File,
    sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() }
): LocalVoiceModelStore = LocalVoiceModelStore(
    File(voiceModelsDirectory, "mediapipe-$id-${sha256.take(16)}"),
    listOf(asset),
    revision = "$revision:$sha256",
    sourceFactory = sourceFactory
)
