package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LocalVoiceModelStatus { CHECKING, NOT_INSTALLED, DOWNLOADING, READY, FAILED }

data class LocalVoiceModelState(
    val status: LocalVoiceModelStatus = LocalVoiceModelStatus.CHECKING,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = LocalVoiceModelStore.TOTAL_BYTES,
    val message: String? = null,
    val transferStage: VoiceTransferStage? = if (status == LocalVoiceModelStatus.CHECKING) VoiceTransferStage.CHECKING else null,
    val failureKind: VoiceAssetFailureKind? = null,
    val httpStatus: Int? = null
)

/** Downloads happen only after an explicit install action; recognition never calls install. */
class LocalVoiceModelStore internal constructor(
    private val root: File,
    private val assets: List<LocalVoiceAsset>,
    private val expandedAssets: List<LocalVoiceAsset> = assets,
    private val expandArchive: (suspend (File) -> Unit)? = null,
    private val revision: String = MODEL_REVISION,
    private val sourceFactory: () -> LocalVoiceAssetSource
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "voice-models")
    )

    internal constructor(voiceModelsDirectory: File) : this(
        File(voiceModelsDirectory, "moonshine-tiny-en-20260821"),
        MODEL_ASSETS,
        sourceFactory = ::HttpsVoiceAssetSource
    )

    private class Shared(val state: MutableStateFlow<LocalVoiceModelState>) {
        val mutex = Mutex()
        var users = 0
        var removalPending = false

        @Volatile var installJob: Job? = null

        @Volatile var source: LocalVoiceAssetSource? = null
    }

    private val totalBytes = assets.sumOf { it.bytes }
    private val shared = stores.getOrPut(root.absolutePath) { Shared(MutableStateFlow(LocalVoiceModelState(totalBytes = totalBytes))) }
    val state: StateFlow<LocalVoiceModelState> = shared.state.asStateFlow()
    private val installed = File(root, "ready")
    private val staging = File(root, "installing")

    fun modelDirectory(): File = installed
    fun isInstalled(): Boolean = state.value.status == LocalVoiceModelStatus.READY

    /** Held through decoder release so remove cannot unlink a model while JNI maps its files. */
    internal fun acquireUse(): Boolean = synchronized(shared) {
        if (shared.removalPending || shared.installJob?.isActive == true) {
            false
        } else if (!verifyInstalledFiles()) {
            shared.state.value = LocalVoiceModelState(
                LocalVoiceModelStatus.NOT_INSTALLED,
                totalBytes = totalBytes,
                message = "Install or repair the local English speech model."
            )
            false
        } else {
            shared.users++
            true
        }
    }

    internal fun releaseUse() = synchronized(shared) { shared.users = (shared.users - 1).coerceAtLeast(0) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        shared.mutex.withLock {
            shared.state.value = LocalVoiceModelState(totalBytes = totalBytes)
            val ready = verifyInstalledFiles()
            shared.state.value = LocalVoiceModelState(
                status = if (ready) LocalVoiceModelStatus.READY else LocalVoiceModelStatus.NOT_INSTALLED,
                downloadedBytes = if (ready) totalBytes else 0,
                totalBytes = totalBytes,
                transferStage = if (ready) VoiceTransferStage.READY else null
            )
        }
    }

    /** Also called on the recognition worker before JNI sees any file. Never performs network IO. */
    internal fun verifyInstalledFiles(): Boolean = try {
        val marker = File(installed, "verified")
        marker.isFile && marker.readText() == revision && verifyFiles(installed)
    } catch (_: IOException) {
        false
    }

    private fun verifyFiles(directory: File): Boolean = expandedAssets.all { asset ->
        verifyAsset(File(directory, asset.name), asset)
    }

    private fun verifyAsset(file: File, asset: LocalVoiceAsset): Boolean =
        file.isFile && file.length() == asset.bytes && digest(file) == asset.sha256

    suspend fun install() = withContext(Dispatchers.IO) {
        // Multiple screens share one installer and its progress. A second tap never queues a download.
        if (!shared.mutex.tryLock()) return@withContext
        try {
            shared.state.value = LocalVoiceModelState(totalBytes = totalBytes)
            if (verifyInstalledFiles()) {
                shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.READY, totalBytes, totalBytes, transferStage = VoiceTransferStage.READY)
                return@withContext
            }
            synchronized(shared) {
                if (shared.users != 0) throw VoiceAssetException(VoiceAssetFailureKind.MODEL_IN_USE, "Local speech is still releasing its model. Try again shortly.")
            }
            shared.installJob = currentCoroutineContext().job
            shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING, totalBytes = totalBytes, transferStage = VoiceTransferStage.VERIFYING)
            if (!root.isDirectory && !root.mkdirs()) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Could not create local speech storage.")
            val verifiedDownloads = assets.filter { asset -> verifyAsset(File(staging, asset.name), asset) }.toSet()
            assets.filterNot { it in verifiedDownloads }.forEach { asset ->
                File(staging, asset.name).delete()
            }
            val partialBytes = (assets - verifiedDownloads).sumOf { asset ->
                val part = File(staging, "${asset.name}.part")
                part.length().takeIf { it in 1 until asset.bytes && validatorFile(part).isFile } ?: 0
            }
            val spaceNeeded = (assets - verifiedDownloads).sumOf { it.bytes } - partialBytes +
                (if (expandArchive != null) expandedAssets.sumOf { it.bytes } else 0) + 16L * 1024 * 1024
            if (root.usableSpace < spaceNeeded) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Free at least ${(spaceNeeded + 1_048_575) / 1_048_576} MiB to install local speech.")
            if (!staging.isDirectory && !staging.mkdirs()) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Could not prepare local speech storage.")
            val source = sourceFactory().also { shared.source = it }
            var complete = 0L
            for (asset in assets) {
                currentCoroutineContext().ensureActive()
                val destination = File(staging, asset.name)
                if (asset in verifiedDownloads) {
                    complete += asset.bytes
                    shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING, complete, totalBytes, transferStage = VoiceTransferStage.VERIFYING)
                    continue
                }
                val partial = File(staging, "${asset.name}.part")
                var lastFailure: IOException? = null
                var downloaded = false
                for (url in asset.urls) {
                    currentCoroutineContext().ensureActive()
                    try {
                        copyVerified(asset, source, url, partial, { stage ->
                            shared.state.value = shared.state.value.copy(transferStage = stage)
                        }) { bytes ->
                            shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING, complete + bytes, totalBytes, transferStage = VoiceTransferStage.TRANSFERRING)
                        }
                        if (!partial.renameTo(destination)) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Could not checkpoint the verified speech asset.")
                        validatorFile(partial).delete()
                        downloaded = true
                        break
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        lastFailure = failure
                        if (!validatorFile(partial).isFile) partial.delete()
                    }
                }
                if (!downloaded) throw lastFailure ?: IOException("The local speech download failed.")
                complete += asset.bytes
            }
            currentCoroutineContext().ensureActive()
            if (expandArchive != null) {
                shared.state.value = shared.state.value.copy(transferStage = VoiceTransferStage.EXTRACTING)
                expandedAssets.forEach { File(staging, it.name).delete() }
                expandArchive.invoke(staging)
                shared.state.value = shared.state.value.copy(transferStage = VoiceTransferStage.VERIFYING)
                if (!verifyFiles(staging)) throw VoiceAssetException(VoiceAssetFailureKind.INTEGRITY, "The extracted speech files could not be verified.")
                assets.forEach { File(staging, it.name).delete() }
            }
            currentCoroutineContext().ensureActive()
            File(staging, "verified").writeText(revision)
            // Only a fully validated directory is published. A partial model can never look ready.
            synchronized(shared) {
                if (shared.users != 0) throw VoiceAssetException(VoiceAssetFailureKind.MODEL_IN_USE, "Local speech is still releasing its model. Try again shortly.")
                if (installed.exists() && !installed.deleteRecursively()) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Could not replace incomplete speech files.")
                if (!staging.renameTo(installed)) throw VoiceAssetException(VoiceAssetFailureKind.STORAGE, "Could not finish installing local speech.")
            }
            shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.READY, totalBytes, totalBytes, transferStage = VoiceTransferStage.READY)
        } catch (cancelled: CancellationException) {
            shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED, totalBytes = totalBytes, message = "Download cancelled.")
            throw cancelled
        } catch (failure: Exception) {
            shared.state.value = shared.state.value.copy(
                status = LocalVoiceModelStatus.FAILED,
                message = failure.message?.take(180) ?: "Could not install local speech. Try again when connected.",
                failureKind = voiceAssetFailureKind(failure),
                httpStatus = (failure as? VoiceAssetException)?.httpStatus
            )
        } finally {
            shared.source?.cancel()
            shared.source = null
            shared.installJob = null
            if (staging.listFiles().orEmpty().none { it.isFile && it.length() > 0 }) staging.deleteRecursively()
            shared.mutex.unlock()
        }
    }

    fun cancelInstall() {
        shared.installJob?.cancel()
        shared.source?.cancel()
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        cancelInstall()
        shared.mutex.withLock {
            synchronized(shared) { shared.removalPending = true }
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                shared.state.value = state.value.copy(message = "Waiting for local speech to release the model…")
                while (synchronized(shared) { shared.users > 0 } && System.nanoTime() < deadline) delay(25)
                synchronized(shared) {
                    if (shared.users > 0) {
                        shared.state.value = state.value.copy(message = "Local speech is still releasing its model. Try removing it again shortly.")
                    } else if (root.exists() && !root.deleteRecursively()) {
                        shared.state.value = state.value.copy(message = "Some local speech files could not be removed. Try again after closing Voice.")
                    } else {
                        shared.state.value = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED, totalBytes = totalBytes)
                    }
                }
            } finally {
                synchronized(shared) { shared.removalPending = false }
            }
        }
    }

    private fun validatorFile(part: File) = File(part.parentFile, "${part.name}.etag")

    private suspend fun copyVerified(asset: LocalVoiceAsset, source: LocalVoiceAssetSource, url: String, destination: File, phase: (VoiceTransferStage) -> Unit, progress: (Long) -> Unit) {
        val checkpoint = validatorFile(destination)
        val prefix = destination.length().takeIf { it in 1 until asset.bytes } ?: 0
        val validator = checkpoint.takeIf { prefix > 0 && it.isFile && it.length() in 1..1024 }?.readLines()
            ?.takeIf { it.size == 2 && it.first() == url }?.last()?.takeIf(::strongVoiceAssetValidator)
        val requestOffset = if (validator == null) 0 else prefix
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var lastProgressAt = 0L
        val deadline = System.nanoTime() + 5L * 60 * 1_000_000_000
        phase(VoiceTransferStage.CONNECTING)
        val transfer = try {
            try {
                source.open(url, requestOffset, validator)
            } catch (_: VoiceRangeRejected) {
                checkpoint.delete()
                source.open(url)
            }
        } catch (failure: IOException) {
            throw voiceDownloadException(failure)
        }
        transfer.use { download ->
            check(download.offset == 0L || download.offset == requestOffset && download.validator == validator)
            if (download.bytes >= 0 && download.bytes != asset.bytes - download.offset) throw VoiceAssetException(VoiceAssetFailureKind.INTEGRITY, "Speech model size does not match the verified release.")
            if (download.offset > 0) {
                destination.inputStream().buffered().use { saved ->
                    val buffer = ByteArray(64 * 1024)
                    while (count < download.offset) {
                        currentCoroutineContext().ensureActive()
                        val bytes = saved.read(buffer, 0, minOf(buffer.size.toLong(), download.offset - count).toInt())
                        if (bytes <= 0) throw IOException("Saved speech download was incomplete.")
                        count += bytes
                        hash.update(buffer, 0, bytes)
                    }
                }
            }
            checkpoint.delete()
            phase(VoiceTransferStage.TRANSFERRING)
            FileOutputStream(destination, count > 0).use { output ->
                download.validator?.takeIf(::strongVoiceAssetValidator)?.let { checkpoint.writeText("$url\n$it") }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() > deadline) throw VoiceAssetException(VoiceAssetFailureKind.TIMEOUT, "Speech download timed out. Check your connection and try again.")
                    val read = try {
                        download.input.read(buffer)
                    } catch (failure: IOException) {
                        throw voiceDownloadException(failure)
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > asset.bytes) {
                        checkpoint.delete()
                        throw VoiceAssetException(VoiceAssetFailureKind.INTEGRITY, "Speech model exceeds its verified size.")
                    }
                    output.write(buffer, 0, read)
                    hash.update(buffer, 0, read)
                    val now = System.nanoTime()
                    if (count == asset.bytes || now - lastProgressAt >= 100_000_000L) {
                        progress(count)
                        lastProgressAt = now
                    }
                }
                output.fd.sync()
            }
        }
        if (count != asset.bytes) throw VoiceAssetException(VoiceAssetFailureKind.NETWORK_IO, "Speech model download ended early. Resume to continue.")
        phase(VoiceTransferStage.VERIFYING)
        if (hash.digest().hex() != asset.sha256) {
            checkpoint.delete()
            throw VoiceAssetException(VoiceAssetFailureKind.INTEGRITY, "Speech model verification failed. The incomplete download was discarded.")
        }
    }

    companion object {
        const val MODEL_NAME = "Moonshine Tiny Streaming · English"
        const val TOTAL_BYTES = 45_233_659L
        const val MODEL_REVISION = "tiny-streaming-en/quantized_26_08_21"
        private val stores = ConcurrentHashMap<String, Shared>()
        private const val CDN = "https://download.moonshine.ai/model/$MODEL_REVISION/"

        // Authoritative release mirror: v0.1.5, resolved to an immutable revision. SHA-256s are
        // its Git LFS OIDs (small ordinary Git files were fetched and hashed). Sizes also match
        // core/moonshine-model-file-metadata.generated.cpp at upstream 234f60faa0eb388b01cdf7e60aca232af37aefda.
        private const val MIRROR = "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/model/$MODEL_REVISION/"
        internal val MODEL_ASSETS = listOf(
            asset("adapter.ort", 1_319_664, "22ecc949e146c49667fda28d102d4e30749a107dc88a396292aa8f277ef1347c"),
            asset("cross_kv.ort", 1_287_544, "143a36667b8d05fd9d04e8c337b7ee121f37ef299aea6b3d82bdb3d3401950b4"),
            asset("decoder_kv.ort", 32_583_720, "8852553f312adb6c9aa4d17418015049b30f412209ee569d336548c0044627de"),
            asset("encoder.ort", 7_675_440, "a8414e1a5dedf9f2093d7680601dd8a9b0433e7020260eafe0e370ead91134ca"),
            asset("frontend.model.ort", 23_344, "5121b561417b638afce0c6c31b760e37c93cf97f80d9b0031aad1fe7b6f25d61"),
            asset("frontend.weights.ort", 2_093_464, "217da24ac6f522ebf02da8ef288e77d1ac68d50d4a6821433182e4fbf4204bbd"),
            asset("streaming_config.json", 509, "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950"),
            asset("tokenizer.bin", 249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
        )

        fun supportsLanguage(tag: String): Boolean = Locale.forLanguageTag(tag).language == "en"

        private fun asset(name: String, bytes: Long, sha256: String) = LocalVoiceAsset(name, bytes, sha256, listOf(CDN + name, MIRROR + name))
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    if (read > 0) hash.update(bytes, 0, read)
                }
            }
            return hash.digest().hex()
        }
    }
}

internal data class LocalVoiceAsset(val name: String, val bytes: Long, val sha256: String, val urls: List<String>)

internal interface LocalVoiceAssetSource {
    fun open(url: String): LocalVoiceAssetStream
    fun open(url: String, offset: Long, validator: String?): LocalVoiceAssetStream = open(url)
    fun cancel()
}

private class VoiceRangeRejected : IOException()

private fun strongVoiceAssetValidator(value: String): Boolean =
    value.length in 2..256 && value.startsWith('"') && value.endsWith('"') && value.none(Char::isISOControl)

internal class LocalVoiceAssetStream(
    val input: InputStream,
    val bytes: Long,
    val offset: Long = 0,
    val validator: String? = null,
    private val disconnect: () -> Unit = {}
) : Closeable {
    override fun close() {
        try {
            input.close()
        } finally {
            disconnect()
        }
    }
}

internal class HttpsVoiceAssetSource : LocalVoiceAssetSource {
    @Volatile private var connection: HttpURLConnection? = null

    @Volatile private var cancelled = false

    override fun open(url: String): LocalVoiceAssetStream = open(url, 0, null)

    override fun open(url: String, offset: Long, validator: String?): LocalVoiceAssetStream {
        var current = URI(url)
        repeat(6) {
            if (cancelled) throw IOException("Download cancelled.")
            if (current.scheme != "https" || current.userInfo != null) throw IOException("Invalid speech model download address.")
            val active = (current.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0 && validator != null) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("If-Range", validator)
                }
            }
            connection = active
            try {
                if (cancelled) throw IOException("Download cancelled.")
                val code = active.responseCode
                if (code !in 100..599) throw VoiceAssetException(VoiceAssetFailureKind.NETWORK_IO, "The voice download server did not return a valid HTTP response.")
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = active.getHeaderField("Location") ?: throw IOException("Speech download redirect was incomplete.")
                    current = current.resolve(location)
                    active.disconnect()
                } else {
                    val etag = active.getHeaderField("ETag")?.takeIf(::strongVoiceAssetValidator)
                    val start = if (code == HttpURLConnection.HTTP_PARTIAL) {
                        val range = active.getHeaderField("Content-Range").orEmpty()
                        if (offset <= 0 || etag == null || etag != validator || !range.startsWith("bytes $offset-")) {
                            throw VoiceRangeRejected()
                        }
                        offset
                    } else {
                        if (code == 416 && offset > 0) throw VoiceRangeRejected()
                        if (code != HttpURLConnection.HTTP_OK) throw VoiceAssetException(VoiceAssetFailureKind.HTTP, "Speech model server returned HTTP $code. Try again when connected.", code)
                        0L
                    }
                    return LocalVoiceAssetStream(active.inputStream, active.contentLengthLong, start, etag) {
                        active.disconnect()
                        if (connection === active) connection = null
                    }
                }
            } catch (failure: Exception) {
                active.disconnect()
                throw failure
            }
        }
        throw IOException("Speech download redirected too many times.")
    }

    override fun cancel() {
        cancelled = true
        connection?.disconnect()
        connection = null
    }
}
