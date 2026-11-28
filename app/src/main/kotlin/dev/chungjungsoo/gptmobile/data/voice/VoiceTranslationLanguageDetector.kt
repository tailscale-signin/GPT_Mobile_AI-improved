package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.languagedetector.LanguageDetector
import dev.chungjungsoo.gptmobile.data.memory.MediaPipeJniContract
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

internal enum class VoiceTranslationLanguageStatus { DETECTED, AMBIGUOUS, OUTSIDE_PAIR, UNAVAILABLE }

internal data class VoiceTranslationLanguageResult(
    val status: VoiceTranslationLanguageStatus,
    val languageTag: String? = null,
    val confidence: Float? = null,
    val reason: String
)

internal data class VoiceTranslationLanguagePrediction(val languageTag: String, val confidence: Float)

internal interface VoiceTranslationLanguageBackend : AutoCloseable {
    fun detect(text: String): List<VoiceTranslationLanguagePrediction>
}

/** The pinned SDK borrows direct model bytes. Unconfirmed retirement retains its owner and seals new native admission. */
internal class VoiceTranslationModelOwners {
    private val retained = mutableSetOf<OwnedBackend>()
    private var admissionClosed = false

    internal val retainedOwnerCount get() = synchronized(this) { retained.size }

    fun create(model: () -> ByteBuffer, open: (ByteBuffer) -> VoiceTranslationLanguageBackend): VoiceTranslationLanguageBackend {
        val owner = synchronized(this) {
            check(!admissionClosed) { "Language detector cleanup is unconfirmed; restart the app before creating another detector." }
            OwnedBackend(model()).also { retained += it }
        }
        try {
            owner.backend = open(owner.model)
        } catch (failure: Throwable) {
            // SDK creation may already have exposed the address to JNI without returning a closeable task.
            synchronized(this) { admissionClosed = true }
            throw failure
        }
        return owner
    }

    private inner class OwnedBackend(val model: ByteBuffer) : VoiceTranslationLanguageBackend {
        var backend: VoiceTranslationLanguageBackend? = null

        @Volatile private var retirementStarted = false

        override fun detect(text: String): List<VoiceTranslationLanguagePrediction> {
            check(!retirementStarted) { "Language detector retirement has started." }
            return checkNotNull(backend).detect(text)
        }

        override fun close() {
            val closing = synchronized(this@VoiceTranslationModelOwners) {
                if (this !in retained) return
                // TaskRunner marks itself stopped before native close, so retrying a failed close cannot prove retirement.
                check(!retirementStarted) { "Language detector cleanup is unconfirmed." }
                retirementStarted = true
                checkNotNull(backend)
            }
            try {
                closing.close()
            } catch (failure: Throwable) {
                synchronized(this@VoiceTranslationModelOwners) { admissionClosed = true }
                throw failure
            } finally {
                Reference.reachabilityFence(model)
            }
            synchronized(this@VoiceTranslationModelOwners) {
                backend = null
                retained -= this
            }
        }
    }
}

/** Owns the bundled model on one worker; neither source text nor predictions are logged or sent over a network. */
internal class VoiceTranslationLanguageDetector internal constructor(
    private val createBackend: () -> VoiceTranslationLanguageBackend,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "voice-translation-language").apply { isDaemon = true }
    }
) : AutoCloseable {
    constructor(context: Context) : this(createBackend = mediaPipeBackend(context.applicationContext))

    private val ownership = Any()

    @Volatile private var closed = false
    private var backend: VoiceTranslationLanguageBackend? = null

    suspend fun detect(text: String, allowedTags: List<String>): VoiceTranslationLanguageResult {
        val pair = translationLanguagePair(allowedTags)
            ?: return unavailable("invalid_pair")
        if (text.length > MAX_TEXT_CHARACTERS) return unavailable("text_too_long")
        if (text.count(Char::isLetter) < 3) {
            return VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.AMBIGUOUS, reason = "insufficient_text")
        }
        return suspendCancellableCoroutine { continuation ->
            synchronized(ownership) {
                if (closed) {
                    continuation.resume(unavailable("closed"))
                } else {
                    worker.execute {
                        if (!continuation.isActive) return@execute
                        val result = try {
                            if (closed) {
                                unavailable("closed")
                            } else {
                                val detector = backend ?: createBackend().also { backend = it }
                                selectTranslationLanguage(detector.detect(text), pair)
                            }
                        } catch (cancelled: CancellationException) {
                            continuation.cancel(cancelled)
                            return@execute
                        } catch (_: Exception) {
                            unavailable("model_unavailable")
                        } catch (_: LinkageError) {
                            unavailable("model_unavailable")
                        }
                        if (continuation.isActive) {
                            continuation.resume(if (closed) unavailable("closed") else result)
                        }
                    }
                }
            }
        }
    }

    /** Closing revokes pending results immediately and releases JNI on its owner after in-flight work finishes. */
    override fun close() {
        synchronized(ownership) {
            if (closed) return
            closed = true
            worker.execute {
                try {
                    backend?.close()
                    backend = null
                } catch (_: Exception) {
                    // Keep the backend; the process registry also retains its borrowed bytes after uncertain cleanup.
                } catch (_: LinkageError) {
                    // A missing native close binding must not crash the application from this worker.
                }
            }
            worker.shutdown()
        }
    }

    companion object {
        internal const val MAX_TEXT_CHARACTERS = 8_000
        internal const val MODEL_ASSET = "voice/language_detector.tflite"
        internal const val MODEL_BYTES = 315_294
        internal const val MODEL_SHA256 = "7db4f23dfe1ad8966b050b419a865da451143fd43eb6b606a256aadeeb1e5417"
        private val nativeModelOwners = VoiceTranslationModelOwners()

        private fun unavailable(reason: String) = VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.UNAVAILABLE, reason = reason)

        private fun verifiedModel(context: Context): ByteBuffer {
            // Verify the exact shipped model before the native task receives it. This is a bounded asset read, not a download.
            val bytes = ByteArray(MODEL_BYTES)
            context.assets.open(MODEL_ASSET).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0)
                    offset += count
                }
                require(input.read() == -1)
            }
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == MODEL_SHA256)
            val model = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                rewind()
            }
            MediaPipeJniContract.verify()
            return model
        }

        private fun mediaPipeBackend(context: Context): () -> VoiceTranslationLanguageBackend = {
            nativeModelOwners.create(model = { verifiedModel(context) }) { model ->
                val detector = LanguageDetector.createFromOptions(
                    context,
                    LanguageDetector.LanguageDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
                        // Keep global competitors: a pair allowlist would turn an unrelated language into a false in-pair choice.
                        .setMaxResults(3)
                        .setScoreThreshold(0f)
                        .build()
                )
                object : VoiceTranslationLanguageBackend {
                    override fun detect(text: String) = detector.detect(text).languagesAndScores().map {
                        VoiceTranslationLanguagePrediction(it.languageCode(), it.probability())
                    }
                    override fun close() = detector.close()
                }
            }
        }
    }
}

private fun translationLanguage(tag: String): String? = runCatching {
    Locale.Builder().setLanguageTag(tag).build().language.lowercase(Locale.ROOT).takeUnless { it.isEmpty() || it == "und" }
}.getOrNull()

private fun translationLanguagePair(tags: List<String>): Map<String, String>? {
    if (tags.size != 2) return null
    val pair = tags.map { tag -> (translationLanguage(tag) ?: return null) to tag }.toMap()
    return pair.takeIf { it.size == 2 }
}

/** A confidence gate, not a guarantee for short names or multilingual text; explicit source selection remains available. */
internal fun selectTranslationLanguage(
    predictions: List<VoiceTranslationLanguagePrediction>,
    allowedTags: List<String>
): VoiceTranslationLanguageResult = translationLanguagePair(allowedTags)?.let { selectTranslationLanguage(predictions, it) }
    ?: VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.UNAVAILABLE, reason = "invalid_pair")

private fun selectTranslationLanguage(
    predictions: List<VoiceTranslationLanguagePrediction>,
    pair: Map<String, String>
): VoiceTranslationLanguageResult {
    fun ambiguous(reason: String, confidence: Float? = null) = VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.AMBIGUOUS, confidence = confidence, reason = reason)
    if (predictions.any { !it.confidence.isFinite() || it.confidence !in 0f..1f }) return ambiguous("invalid_scores")
    val ranked = predictions.groupBy { translationLanguage(it.languageTag) }
        .map { (language, values) -> language to values.maxOf { it.confidence } }
        .sortedByDescending { it.second }
    val first = ranked.firstOrNull() ?: return ambiguous("no_prediction")
    if (first.first == null) return ambiguous("unknown_language", first.second)
    if (first.second < 0.80f) return ambiguous("low_confidence", first.second)
    if (first.second - (ranked.getOrNull(1)?.second ?: 0f) < 0.20f) return ambiguous("close_scores", first.second)
    val selected = pair[first.first]
        ?: return VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.OUTSIDE_PAIR, confidence = first.second, reason = "outside_pair")
    return VoiceTranslationLanguageResult(VoiceTranslationLanguageStatus.DETECTED, selected, first.second, "detected")
}
