package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import dev.melo.kokoro.KokoroFrontend
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Explicit opt-in to the saved PC connection. No automatic discovery or public speech relay. */
internal class GatewayVoicePhraseEngineFactory(
    private val base: String,
    private val credential: String?,
    private val report: (String) -> Unit
) : VoicePhraseEngineFactory {
    override val selectedBackend = VoiceSpeechBackend.GATEWAY_CUDA
    private val pending = AtomicReference<GatewayVoicePhraseEngine?>(null)
    private val generation = AtomicLong()
    override fun cancelPending() {
        generation.incrementAndGet()
        pending.get()?.cancel()
    }

    override val identity = "gateway-cuda-pcm-v3-${KokoroModelPack.MODEL_SHA256}-" + MessageDigest.getInstance("SHA-256")
        .digest("$base\n${credential.orEmpty()}".toByteArray()).joinToString("") { "%02x".format(it) }

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        val ticket = generation.get()
        val engine = GatewayVoicePhraseEngine(GatewayVoiceProtocol.endpoint(base), credential, directory, lexicon, report)
        pending.set(engine)
        try {
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            engine.qualify()
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            return engine
        } catch (error: Throwable) {
            runCatching { engine.close() }
            if (error is CancellationException || error is Error) throw error
            report("Gateway CUDA speech is unavailable. Check CUDA speech setup and the selected PC connection.")
            throw IllegalStateException("Gateway CUDA speech is unavailable. Select CPU speech or check the selected PC.", error)
        } finally {
            pending.compareAndSet(engine, null)
        }
    }
}

private class GatewayVoicePhraseEngine(
    private val endpoint: String,
    private val credential: String?,
    directory: File,
    lexicon: String,
    private val report: (String) -> Unit
) : VoicePhraseEngine {
    override val executionProvider = "gateway-cuda"
    override val supportsStreaming: Boolean get() = streamingAvailable
    private val frontend = KokoroFrontend(File(directory, KokoroModelPack.TOKENS).absolutePath, File(directory, lexicon).absolutePath)
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val request = AtomicReference<String?>(null)
    private val running = AtomicReference<Job?>(null)
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var model = ""
    private var streamingAvailable = false
    private val http = HttpClient(OkHttp) {
        install(dev.chungjungsoo.gptmobile.data.network.OkHttpConversationPrivacy)
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        engine {
            config {
                addInterceptor(dev.chungjungsoo.gptmobile.data.network.ConversationPrivacyHttp.bridge)
                addNetworkInterceptor { chain ->
                    val privacy = dev.chungjungsoo.gptmobile.data.network.ConversationPrivacyHttp.policy(chain.request())
                    privacy.recheckAuthorization()
                    if (privacy.conversation?.enabled == true &&
                        chain.request().url.encodedPath in setOf("/gateway/voice/synthesize", GatewayVoicePcmStream.PATH)
                    ) {
                        throw java.io.IOException("PRIVACY_SERVICE_UNVERIFIED: Private speech stays on this phone.")
                    }
                    chain.proceed(chain.request())
                }
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
                proxy(java.net.Proxy.NO_PROXY)
            }
        }
    }

    private val transport = GatewayVoiceTransport(http, endpoint, credential)

    fun qualify() = runBlocking(ConversationPrivacyContext.capture() ?: ConversationPrivacyContext(null, false)) {
        val job = currentCoroutineContext()[Job]!!
        running.set(job)
        try {
            if (cancelled.get() || closed.get()) throw CancellationException("Speech cancelled")
            val capability = withTimeout(10_000) {
                transport.exchange("GET", "/gateway/voice/capabilities", null, 16_384)
            }
            model = GatewayVoiceProtocol.capabilityModel(capability)
            streamingAvailable = capability.optString("stream_protocol") == GatewayVoicePcmStream.PROTOCOL
            if (cancelled.get() || closed.get()) throw CancellationException("Speech cancelled")
            report("Gateway CUDA execution verified. Reply pronunciation tokens are sent to your selected PC.")
        } finally {
            running.compareAndSet(job, null)
        }
    }

    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
        if (closed.get() || cancelled.get()) throw CancellationException("Speech cancelled")
        val phrase = frontend.encode(text)
        val id = UUID.randomUUID().toString()
        request.set(id)
        try {
            return runBlocking(ConversationPrivacyContext.capture() ?: ConversationPrivacyContext(null, false)) {
                val job = currentCoroutineContext()[Job]!!
                running.set(job)
                if (cancelled.get() || closed.get()) throw CancellationException("Speech cancelled")
                try {
                    val payload = GatewayVoiceProtocol.request(id, model, phrase.tokenIds, phrase.wordTokens, speaker, speed)
                    val response = try {
                        transport.exchange("POST", "/gateway/voice/synthesize", payload, GatewayVoiceProtocol.MAX_RESPONSE_BYTES)
                    } catch (error: UnverifiedServiceTransport) {
                        report("Privacy Mode keeps speech on this phone. Select on-device CPU, GPU or NPU speech.")
                        throw error
                    }
                    if (cancelled.get() || closed.get()) throw CancellationException("Speech cancelled")
                    GatewayVoiceProtocol.response(response, id, model, phrase.tokenIds.size, phrase.wordTokens)
                } finally {
                    running.compareAndSet(job, null)
                }
            }
        } finally {
            request.compareAndSet(id, null)
        }
    }

    override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
        check(streamingAvailable) { "Gateway PCM streaming is unavailable" }
        if (closed.get() || cancelled.get()) throw CancellationException("Speech cancelled")
        val phrase = frontend.encode(text)
        val id = UUID.randomUUID().toString()
        request.set(id)
        try {
            runBlocking(ConversationPrivacyContext.capture() ?: ConversationPrivacyContext(null, false)) {
                val job = currentCoroutineContext()[Job]!!
                running.set(job)
                try {
                    if (cancelled.get() || closed.get()) throw CancellationException("Speech cancelled")
                    val payload = GatewayVoiceProtocol.request(id, model, phrase.tokenIds, phrase.wordTokens, speaker, speed)
                    transport.stream(payload, phrase.tokenIds.size, phrase.wordTokens, text) { chunk ->
                        if (cancelled.get() || closed.get() || !emit(chunk)) {
                            this@GatewayVoicePhraseEngine.cancel()
                            false
                        } else {
                            true
                        }
                    }
                } catch (error: UnverifiedServiceTransport) {
                    report("Privacy Mode keeps speech on this phone. Select on-device CPU, GPU or NPU speech.")
                    throw error
                } finally {
                    running.compareAndSet(job, null)
                }
            }
        } finally {
            request.compareAndSet(id, null)
        }
    }

    override fun resetCancellation() {
        check(!closed.get())
        cancelled.set(false)
    }

    override fun cancel() {
        cancelled.set(true)
        // Capture the identity before coroutine cancellation clears its finally block.
        val id = request.getAndSet(null)
        running.get()?.cancel(CancellationException("Speech cancelled"))
        if (id != null && !closed.get()) {
            cancellationScope.launch {
                runCatching { withTimeout(5_000) { transport.exchange("POST", "/gateway/voice/cancel", JSONObject().put("request_id", id), 16_384) } }
            }
        }
    }

    override fun close() {
        if (closed.get()) return
        cancel()
        if (!closed.compareAndSet(false, true)) return
        frontend.close()
        cancellationScope.launch {
            // Let a dispatched cancellation reach the PC before closing this connection.
            val owner = currentCoroutineContext()[Job]
            cancellationScope.coroutineContext[Job]?.children?.filter { it != owner }?.toList()?.forEach { it.join() }
            http.close()
            cancellationScope.cancel()
        }
    }
}
