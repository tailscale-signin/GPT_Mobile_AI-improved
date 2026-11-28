package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A separate verified pack keeps caption alignment independent of microphone model removal. */
class PocketCaptionModelStore internal constructor(
    private val store: LocalVoiceModelStore,
    private val recognitionStore: LocalVoiceModelStore,
    private val reusableAssets: List<LocalVoiceAsset> = LocalVoiceModelStore.MODEL_ASSETS,
    private val sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() }
) {
    constructor(context: Context) : this(
        LocalVoiceModelStore(
            root = File(context.applicationContext.noBackupFilesDir, "voice-models/pocket-captions-$MODEL_SHA256"),
            assets = MODEL_ASSETS,
            revision = "$IMPLEMENTATION_ID:$MODEL_SHA256",
            sourceFactory = { HttpsVoiceAssetSource() }
        ),
        LocalVoiceModelStore(context.applicationContext)
    )

    val state = store.state
    fun isReady(): Boolean = store.isInstalled()
    fun modelDirectory(): File = store.modelDirectory()
    suspend fun refresh(forceVerification: Boolean = true) = store.refresh(forceVerification)
    suspend fun remove() = store.remove()
    fun cancelInstall() = store.cancelInstall()

    /** Only this explicit installation action may download. Verified STT bytes need no second transfer. */
    suspend fun install() = withContext(Dispatchers.IO) {
        val leased = recognitionStore.acquireUseAfterInstall()
        try {
            store.install(waitForOwner = false, sourceOverride = {
                if (leased) ReusingCaptionSource(recognitionStore.modelDirectory(), reusableAssets, sourceFactory()) else sourceFactory()
            })
        } finally {
            if (leased) recognitionStore.releaseUse()
        }
    }

    internal fun acquire(): ModelLease? =
        if (store.acquireUse()) ModelLease(store.modelDirectory(), store::releaseUse) else null

    internal class ModelLease(val directory: File, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    companion object {
        const val IMPLEMENTATION_ID = "moonshine-0.1.6-pocket-final-v1"
        const val MODEL_NAME = "Pocket English audio caption alignment"
        const val MODEL_REVISION = "tiny-streaming-en/quantized_26_08_21"
        const val MODEL_SHA256 = "cb069e5b36a52ef17b15dbcf38689c038a3b75a2f0a399babd09eec70dc7b95d"
        const val TOTAL_BYTES = 77_748_675L
        const val ADDITIONAL_DOWNLOAD_BYTES = 32_515_016L
        private const val MIRROR = "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/model/$MODEL_REVISION/"

        // Publisher LFS digest, independently checked against the downloaded bytes. Keep this
        // decoder with the matching 2026-08-21 weights; the later export is a different model.
        internal val ATTENTION_ASSET = LocalVoiceAsset(
            "decoder_kv_with_attention.ort",
            ADDITIONAL_DOWNLOAD_BYTES,
            "c1b41adca4ef75ad8dfdfc71875b5d0ae9be959865a14498da01cbddab8c02b8",
            listOf(MIRROR + "decoder_kv_with_attention.ort")
        )
        internal val MODEL_ASSETS = LocalVoiceModelStore.MODEL_ASSETS + ATTENTION_ASSET
    }
}

/** The caller holds the verified source generation's lease through every local copy. */
private class ReusingCaptionSource(
    private val directory: File,
    assets: List<LocalVoiceAsset>,
    private val network: LocalVoiceAssetSource
) : LocalVoiceAssetSource {
    private val local = assets.flatMap { asset -> asset.urls.map { it to asset } }.toMap()
    private val active = AtomicReference<InputStream?>()
    private val cancelled = AtomicBoolean()

    override fun open(url: String) = open(url, 0, null)

    override fun open(url: String, offset: Long, validator: String?): LocalVoiceAssetStream {
        if (cancelled.get()) throw IOException("Caption model installation cancelled.")
        val asset = local[url] ?: return network.open(url, offset, validator)
        val identity = "\"${asset.sha256}\""
        val start = offset.takeIf { it in 0..asset.bytes && validator == identity } ?: 0
        val input = FileInputStream(File(directory, asset.name))
        try {
            input.channel.position(start)
            active.set(input)
            if (cancelled.get()) throw IOException("Caption model installation cancelled.")
            return LocalVoiceAssetStream(input, asset.bytes - start, start, identity) { active.compareAndSet(input, null) }
        } catch (failure: Throwable) {
            active.compareAndSet(input, null)
            input.close()
            throw failure
        }
    }

    override fun cancel() {
        cancelled.set(true)
        try {
            active.getAndSet(null)?.close()
        } finally {
            network.cancel()
        }
    }
}
