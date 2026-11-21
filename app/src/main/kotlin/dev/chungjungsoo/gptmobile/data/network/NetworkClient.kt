package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool

@Singleton
class NetworkClient @Inject constructor(
    private val httpEngine: HttpClientEngineFactory<*>
) {
    private val idleConnectionPool = ConnectionPool(32, 10, TimeUnit.MINUTES)
    private val gatewayReadConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    /** Metadata recovery cannot evict the provider/streaming pool or close TLS sockets on Main. */
    internal suspend fun evictIdleConnections() = withContext(Dispatchers.IO) { gatewayReadConnectionPool.evictAll() }

    private val client by lazy { createClient(idleConnectionPool) }
    private val gatewayReads by lazy { createClient(gatewayReadConnectionPool) }

    /** Only bounded gateway GETs use this pool; mutations and event streams keep the ordinary client. */
    internal fun gatewayReadClient(): HttpClient = gatewayReads

    private val maskedInference by lazy { createClient(idleConnectionPool, maskedProvider = true) }

    internal suspend fun inferenceClient(): HttpClient =
        if (kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.privacy.MaskedProviderCall.Key] != null) maskedInference else client

    private fun createClient(pool: ConnectionPool, maskedProvider: Boolean = false): HttpClient = HttpClient(httpEngine) {
        if (!maskedProvider) install(PrivateMetadataGate)
        install(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsHttpPlugin)
        if (httpEngine == OkHttp) {
            install(ReadinessHttpPlugin)
            install(OkHttpReadinessGrant)
        }
        expectSuccess = false
        followRedirects = false

        // Optimize CIO engine with TCP keep-alive and connection limits when CIO is used
        if (httpEngine == CIO) {
            engine {
                (this as? CIOEngineConfig)?.apply {
                    maxConnectionsCount = 1000
                    endpoint {
                        maxConnectionsPerRoute = 100
                        pipelineMaxSize = 20
                        keepAliveTime = 300_000L
                        connectTimeout = 60_000L
                        connectAttempts = 5
                    }
                }
            }
        } else if (httpEngine == OkHttp) {
            engine {
                (this as? OkHttpConfig)?.config {
                    addInterceptor(ReadinessHttpTrace.stripHeader)
                    addInterceptor(PrivateGatewayMetadata.stripHeader)
                    addNetworkInterceptor { chain ->
                        val request = chain.request()
                        if (!request.url.isHttps) {
                            val route = chain.connection()?.route()
                            if (route == null ||
                                route.proxy.type() != java.net.Proxy.Type.DIRECT ||
                                !isPrivateHttpPeer(route.socketAddress.address)
                            ) {
                                throw java.net.ProtocolException("Public provider connections require HTTPS.")
                            }
                        }
                        if (!maskedProvider && dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.active()) {
                            if (!PrivateGatewayMetadata.allows(request)) throw PrivateMetadataDenied()
                        }
                        chain.proceed(request)
                    }
                    eventListenerFactory(ReadinessHttpTrace.listener)
                    retryOnConnectionFailure(false)
                    connectTimeout(60, TimeUnit.SECONDS)
                    readTimeout(5, TimeUnit.MINUTES)
                    writeTimeout(5, TimeUnit.MINUTES)
                    callTimeout(0, TimeUnit.MILLISECONDS)
                    pingInterval(30, TimeUnit.SECONDS)
                    connectionPool(pool)
                }
            }
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(SSE)

        install(HttpTimeout) {
            // Non-streaming calls get a generous ceiling. Streaming requests override
            // this with an unlimited request deadline and a five-minute idle timeout.
            requestTimeoutMillis = 900_000L
            connectTimeoutMillis = 60_000L
            socketTimeoutMillis = 300_000L
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    val correlated = LocalMetadataReadAttempt.correlateLog(message)
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Network", correlated)
                    if (diagnosticsEnabled && !dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.enabled.value) Logger.DEFAULT.log(dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage(correlated))
                }
            }
            level = LogLevel.HEADERS
            filter { diagnosticsEnabled || dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.enabled.value }
            sanitizeHeader { header -> isSensitiveHeader(header) }
        }

        install(DefaultRequest) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }
    }

    operator fun invoke(): HttpClient = client

    companion object {
        @Volatile var diagnosticsEnabled: Boolean = false

        // Default JSON config (used for most APIs)
        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            useArrayPolymorphism = false
            encodeDefaults = true
            explicitNulls = false
        }

        // OpenAI-specific JSON config with "type" discriminator for MessageContent
        val openAIJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            useArrayPolymorphism = false
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = false
        }

        internal fun resolveNetworkLogLevel(): LogLevel = if (dev.chungjungsoo.gptmobile.BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE

        internal fun isPrivateHttpPeer(address: java.net.InetAddress?): Boolean {
            if (address == null || address.isAnyLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            return address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                (bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 255) in 64..127) ||
                (bytes.size == 16 && (bytes[0].toInt() and 254) == 252)
        }

        // Profiles may supply arbitrary vendor-specific headers. Only retain header
        // values with a defined, non-personal meaning in HTTP diagnostics.
        internal fun isSensitiveHeader(header: String): Boolean =
            header.lowercase(java.util.Locale.ROOT) !in diagnosticHeaders

        private val diagnosticHeaders = setOf(
            "content-length",
            "transfer-encoding",
            "connection",
            "retry-after",
            "content-type"
        )
    }
}
