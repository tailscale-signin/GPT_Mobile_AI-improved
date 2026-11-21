package dev.chungjungsoo.gptmobile.data.network.gateway

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The private key never leaves this Android installation or enters a backup. */
@Singleton
internal class GatewayControlDeviceKeys @Inject constructor() {
    private val lock = Any()

    suspend fun publicKey(alias: String): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build()
                    )
                }.generateKeyPair()
            }
            Base64.getEncoder().encodeToString(store.getCertificate(alias).publicKey.encoded)
        }
    }

    suspend fun sign(alias: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey(alias, null) as? java.security.PrivateKey ?: error("Pair this Android installation again on the Gateway.")
            Base64.getEncoder().encodeToString(
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(key)
                    update(bytes)
                    sign()
                }
            )
        }
    }
}

internal fun gatewayControlSigningText(installationId: String, method: String, path: String, time: Long, nonce: String, body: String): String {
    require(method in setOf("GET", "POST", "PATCH", "PUT", "DELETE"))
    require(path.startsWith("/gateway/") && !path.contains('#') && path.none(Char::isISOControl))
    require(installationId.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) && nonce.matches(Regex("[a-f0-9]{32}")) && time > 0)
    return "gptmobile-control-v1\n$installationId\n$method\n$path\n$time\n$nonce\n${gatewayControlDigest(body)}"
}

internal fun gatewayControlCanonicalPath(endpoint: String, path: String): String {
    require(path.startsWith("/gateway/") && !path.contains('#') && !path.contains('\\') && path.none(Char::isISOControl))
    val url = Url(gatewayControlEndpoint(endpoint) + path)
    return url.encodedPath + url.encodedQuery.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
}

/** A self-signed local certificate is trusted only after the owner supplies its exact fingerprint. */
internal class GatewayCertificatePin(private val fingerprint: String) : X509TrustManager {
    init {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Gateway client certificates are unsupported.")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Gateway certificate is missing.")
        leaf.checkValidity()
        val observed = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(observed, expected)) throw CertificateException("Gateway certificate changed. Pair with the selected PC again.")
    }
}

internal class GatewaySignedRequest(val http: HttpClient, val endpoint: String, val token: String, val headers: Map<String, String>) {
    fun requestHeaders(existing: Map<String, String>): Map<String, String> = existing.filterKeys { name ->
        !name.equals("Authorization", true) && headers.keys.none { it.equals(name, true) }
    } + headers
}
internal data class GatewayControlResponse(val status: Int, val body: String)

/** No redirects, implicit retries, shared inference credentials, diagnostic logging or public trust fallback. */
@Singleton
internal class GatewayControlTransport @Inject constructor(private val keys: GatewayControlDeviceKeys) {
    private val accountClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
            }
            engine {
                config {
                    proxy(java.net.Proxy.NO_PROXY)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                }
            }
        }
    }

    suspend fun accountExchange(binding: GatewayControlBinding, method: String, path: String, body: String): GatewayControlResponse {
        require(binding.connectedAccount && (path == "/gateway/settings" || path == "/gateway/settings/connection" || path == "/gateway/updates" || path.matches(Regex("/gateway/settings/commands/[A-Za-z0-9_-]{8,100}"))))
        val endpoint = binding.originalEndpoint.removeSuffix("/v1").trimEnd('/') + "/gateway/engineering/connected" + path.removePrefix("/gateway")
        return accountClient.prepareRequest(endpoint) {
            this.method = HttpMethod.parse(method)
            header("X-Gateway-Installation-ID", binding.installationId)
            header("X-Gateway-Principal-ID", binding.principalId)
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.execute { GatewayControlResponse(it.status.value, it.boundedMetadata(256 * 1024)) }
    }

    private val clients = linkedMapOf<String, HttpClient>()

    private fun client(endpoint: String, fingerprint: String): HttpClient = synchronized(clients) {
        val origin = gatewayControlEndpoint(endpoint)
        val identity = "$origin|$fingerprint"
        clients.getOrPut(identity) {
            val trust = GatewayCertificatePin(fingerprint)
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            HttpClient(OkHttp) {
                expectSuccess = false
                followRedirects = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 30_000
                    socketTimeoutMillis = 30_000
                }
                engine {
                    config {
                        sslSocketFactory(tls.socketFactory, trust)
                        // OkHttp's normal hostname verifier additionally checks the SAN.
                        followRedirects(false)
                        followSslRedirects(false)
                        retryOnConnectionFailure(false)
                        connectTimeout(10, TimeUnit.SECONDS)
                        readTimeout(30, TimeUnit.SECONDS)
                    }
                }
            }
        }
    }

    suspend fun signed(endpoint: String, fingerprint: String, installationId: String, token: String, keyAlias: String, method: String, path: String, body: String = ""): GatewaySignedRequest {
        require(token.length in 1..2048 && token.none(Char::isISOControl))
        val origin = gatewayControlEndpoint(endpoint)
        val canonicalPath = gatewayControlCanonicalPath(origin, path)
        val time = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val message = gatewayControlSigningText(installationId, method, canonicalPath, time, nonce, body)
        val signature = keys.sign(keyAlias, message.toByteArray(Charsets.UTF_8))
        return GatewaySignedRequest(
            client(origin, fingerprint),
            origin + canonicalPath,
            token,
            mapOf(
                "X-Gateway-Installation-ID" to installationId,
                "X-Gateway-Device-Time" to time.toString(),
                "X-Gateway-Device-Nonce" to nonce,
                "X-Gateway-Device-Signature" to signature
            )
        )
    }

    suspend fun exchange(endpoint: String, fingerprint: String, installationId: String, token: String, keyAlias: String, method: String, path: String, body: String = ""): GatewayControlResponse {
        val signed = signed(endpoint, fingerprint, installationId, token, keyAlias, method, path, body)
        return signed.http.prepareRequest(signed.endpoint) {
            this.method = HttpMethod.parse(method)
            bearerAuth(signed.token)
            signed.headers.forEach { (key, value) -> header(key, value) }
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.execute { response -> GatewayControlResponse(response.status.value, response.boundedMetadata(256 * 1024)) }
    }
}

internal interface GatewayPairedRequestSource {
    /** An explicitly retained different principal always keeps its original route. */
    suspend fun prepare(config: ProviderRequestConfig, method: String, path: String, body: String = ""): GatewaySignedRequest?

    /** Distinguishes paired authority from the unchanged inference profile for read caches. */
    suspend fun identity(config: ProviderRequestConfig): String? = null
}
