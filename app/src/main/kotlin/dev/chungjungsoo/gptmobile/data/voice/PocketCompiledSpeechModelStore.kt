package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One verified, leased installation shared by the experimental GPU and NPU routes. */
class PocketCompiledSpeechModelStore internal constructor(private val store: LocalVoiceModelStore) {
    constructor(context: Context) : this(
        LocalVoiceModelStore(
            root = File(context.applicationContext.noBackupFilesDir, "voice-models/pocket-compiled-${PocketCompiledSpeechCatalog.MODEL_REVISION}"),
            assets = PocketCompiledSpeechCatalog.assets,
            revision = "pocket-compiled:${PocketCompiledSpeechCatalog.MODEL_REVISION}:${PocketCompiledSpeechCatalog.TOKENIZER_REVISION}",
            sourceFactory = { HttpsVoiceAssetSource() }
        )
    )

    val state = store.state
    fun isReady(): Boolean = store.isInstalled()
    fun modelDirectory(): File = store.modelDirectory()
    suspend fun refresh(forceVerification: Boolean = true) = store.refresh(forceVerification)
    suspend fun install() = store.install()
    suspend fun remove() = store.remove()
    fun cancelInstall() = store.cancelInstall()

    internal fun acquire(): ModelLease {
        check(store.acquireUse()) { "Install or repair the compiled Pocket English model pack." }
        return ModelLease(store.modelDirectory(), store::releaseUse)
    }

    internal class ModelLease(val directory: File, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
