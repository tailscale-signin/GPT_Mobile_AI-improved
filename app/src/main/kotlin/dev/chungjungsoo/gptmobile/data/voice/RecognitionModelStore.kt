package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Verified model ownership shared by setup, warm recognizers and active microphone sessions. */
internal class RecognitionModelStore internal constructor(
    val pack: VoiceRecognitionModelPack,
    private val store: LocalVoiceModelStore
) {
    constructor(
        context: Context,
        model: VoiceRecognitionModel,
        compute: VoiceRecognitionCompute = VoiceRecognitionCompute.CPU,
        legacyTinyStore: LocalVoiceModelStore? = null
    ) : this(File(context.applicationContext.noBackupFilesDir, "voice-models"), model, compute, legacyTinyStore)

    constructor(
        voiceModelsDirectory: File,
        model: VoiceRecognitionModel,
        compute: VoiceRecognitionCompute = VoiceRecognitionCompute.CPU,
        legacyTinyStore: LocalVoiceModelStore? = null,
        sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() }
    ) : this(
        requireNotNull(VoiceRecognitionModelCatalog.pack(model, compute)) { "This model does not provide a $compute model pack." },
        createStore(voiceModelsDirectory, model, compute, legacyTinyStore, sourceFactory)
    )

    val model: VoiceRecognitionModel get() = pack.model
    val compute: VoiceRecognitionCompute get() = pack.compute
    val state = store.state
    fun isReady(): Boolean = store.isInstalled()
    fun isInstalled(): Boolean = isReady()
    fun modelDirectory(): File = store.modelDirectory()
    suspend fun refresh(forceVerification: Boolean = true) = store.refresh(forceVerification)
    suspend fun install() = store.install()
    suspend fun remove() = store.remove()
    fun cancelInstall() = store.cancelInstall()

    /** Legacy capture retains a balanced reference until its native worker really retires. */
    fun acquireUse(): Boolean = store.acquireUse()
    fun releaseUse() = store.releaseUse()

    fun acquire(): ModelLease? =
        if (acquireUse()) ModelLease(modelDirectory(), pack, ::releaseUse) else null

    internal class ModelLease(
        val directory: File,
        val pack: VoiceRecognitionModelPack,
        private val release: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    companion object {
        private fun createStore(
            voiceModelsDirectory: File,
            model: VoiceRecognitionModel,
            compute: VoiceRecognitionCompute,
            legacyTinyStore: LocalVoiceModelStore?,
            sourceFactory: () -> LocalVoiceAssetSource
        ): LocalVoiceModelStore {
            val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(model, compute)) { "This model does not provide a $compute model pack." }
            // Preserve the original Tiny root and shared install mutex, leases and verified bytes.
            if (model == VoiceRecognitionModel.MOONSHINE_TINY) return legacyTinyStore ?: LocalVoiceModelStore(voiceModelsDirectory, sourceFactory)
            return LocalVoiceModelStore(
                root = File(voiceModelsDirectory, "recognition-${pack.id}-${pack.modelIdentity.take(16)}"),
                assets = pack.assets,
                expandedAssets = pack.expandedAssets,
                expandArchive = pack.archiveDirectory?.let { { directory -> unpack(directory, pack) } },
                revision = "${pack.id}:${pack.revision}:${pack.modelIdentity}",
                sourceFactory = sourceFactory
            )
        }

        /** The downloader checks the complete archive before invoking this bounded extractor. */
        internal suspend fun unpack(directory: File, pack: VoiceRecognitionModelPack) {
            val archiveDirectory = requireNotNull(pack.archiveDirectory)
            val archive = pack.assets.single()
            val expected = pack.expandedAssets.associateBy { "$archiveDirectory/${it.name}" }
            val extracted = mutableSetOf<String>()
            val buffer = ByteArray(64 * 1024)
            File(directory, archive.name).inputStream().buffered().use { source ->
                TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextEntry ?: break
                        val asset = expected[entry.name] ?: continue
                        if (!entry.isFile || entry.isLink || entry.isSymbolicLink || entry.isSparse || entry.size != asset.bytes || !extracted.add(asset.name)) {
                            throw IOException("The speech package has an invalid model file.")
                        }
                        // Archive names never become destination paths; only the pinned manifest does.
                        File(directory, asset.name).outputStream().use { output ->
                            var remaining = asset.bytes
                            while (remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count <= 0) throw IOException("The speech package ended before all model files were extracted.")
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                            output.fd.sync()
                        }
                    }
                }
            }
            if (extracted.size != expected.size) throw IOException("The speech package is missing required model files.")
        }
    }
}
