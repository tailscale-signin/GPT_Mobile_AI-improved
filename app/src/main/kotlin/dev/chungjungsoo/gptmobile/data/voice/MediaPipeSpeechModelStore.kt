package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.io.IOException
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
    sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() }
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        File(context.applicationContext.noBackupFilesDir, "voice-models"),
        File(context.applicationContext.cacheDir, "mediapipe-speech"),
        scope
    )

    init {
        require(manifests.isNotEmpty() && manifests.map { it.engine }.distinct().size == manifests.size)
    }

    private val stores = manifests.associate { model ->
        model.engine to LocalVoiceModelStore(
            File(voiceModelsDirectory, "mediapipe-${model.id}-${model.sha256.take(16)}"),
            listOf(model.asset),
            revision = "${model.revision}:${model.sha256}",
            sourceFactory = sourceFactory
        )
    }

    val state: StateFlow<Map<MediaPipeSpeechEngine, LocalVoiceModelState>> = combine(stores.values.map { it.state }) { values ->
        stores.keys.zip(values.toList()).toMap()
    }.stateIn(scope, SharingStarted.Eagerly, stores.mapValues { it.value.state.value })

    fun localStore(engine: MediaPipeSpeechEngine): LocalVoiceModelStore = stores.getValue(engine)

    suspend fun refresh(engine: MediaPipeSpeechEngine? = null) {
        if (engine == null) stores.values.forEach { it.refresh() } else localStore(engine).refresh()
    }

    suspend fun install(engine: MediaPipeSpeechEngine) = localStore(engine).install()

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
            val directory = File(parent, "shared-${backend.name.lowercase()}-${speakingRate.toBits().toUInt().toString(16)}-bucket-$targetBucket")
            synchronized(cacheUsers) {
                // Cache keys include every exposed SDK configuration input. Keep the current
                // directory and one previous unused configuration; never remove an active lease.
                val unused = parent.listFiles().orEmpty().filter { it != directory && cacheUsers[it.absolutePath] == null }
                    .sortedByDescending(File::lastModified)
                unused.drop(1).forEach {
                    if (!it.deleteRecursively()) throw IOException("Could not clear an old speech engine cache.")
                }
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not prepare the speech engine cache.")
                directory.setLastModified(System.currentTimeMillis())
                cacheUsers[directory.absolutePath] = (cacheUsers[directory.absolutePath] ?: 0) + 1
            }
            return ModelLease(File(store.modelDirectory(), model.fileName), directory) {
                synchronized(cacheUsers) {
                    val count = cacheUsers.getValue(directory.absolutePath)
                    if (count == 1) cacheUsers.remove(directory.absolutePath) else cacheUsers[directory.absolutePath] = count - 1
                    // Concurrent preparations can temporarily keep extra leased directories.
                    // Once they retire, retain at most two unused configurations as well.
                    parent.listFiles().orEmpty().filter { cacheUsers[it.absolutePath] == null }
                        .sortedByDescending(File::lastModified).drop(2).forEach { it.deleteRecursively() }
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

    internal class ModelLease(val file: File, val cacheDirectory: File, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private companion object {
        val cacheUsers = mutableMapOf<String, Int>()
    }
}
