package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Public native client. The project's broker owns the confidential client secret. */
internal class GitHubPublicAuthClient(
    http: HttpClient,
    private val brokerOrigin: String,
    private val callbackUri: String,
    private val clientId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) }
) : AutoCloseable {
    private val client = http.config {
        followRedirects = false
        expectSuccess = false
    }

    init {
        requireHttps(brokerOrigin, originOnly = true)
        requireHttps(callbackUri)
        require(GitHubDeviceAuthClient.validClientId(clientId)) { "The project GitHub App is not configured." }
    }

    @Serializable
    data class Pending(
        val connectionUid: String,
        val brokerOrigin: String,
        val callbackUri: String,
        val clientId: String,
        val state: String,
        val verifier: String,
        val expiresAt: Long
    ) {
        override fun toString() = "GitHub authorization pending (credentials redacted)"
    }

    data class Start(val authorizationUri: String, val pending: Pending) {
        override fun toString() = "GitHub authorization start (credentials redacted)"
    }

    suspend fun begin(connectionUid: String): Start {
        require(connectionUid.isNotBlank())
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32))
        val challenge = challenge(verifier)
        val started = now()
        val response = post("start", buildJsonObject { put("codeChallenge", challenge) })
        val state = response.text("state")
        check(state.matches(Regex("[A-Za-z0-9_-]{43}"))) { "GitHub sign-in returned invalid state." }
        val lifetime = response.number("expiresIn")?.takeIf { it in 1..600 } ?: error("GitHub sign-in returned an invalid expiration.")
        check(response.text("redirectUri") == callbackUri) { "GitHub callback configuration does not match this app." }
        val authorization = response.text("authorizationUrl")
        val uri = runCatching { URI(authorization) }.getOrNull() ?: error("Invalid GitHub authorization address.")
        check(uri.scheme == "https" && uri.host == "github.com" && uri.path == "/login/oauth/authorize" && uri.port == -1 && uri.userInfo == null && uri.fragment == null) { "Unexpected GitHub authorization address." }
        val parameters = parameters(uri.rawQuery)
        check(
            parameters.keys == setOf("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "scope") &&
                parameters["client_id"] == clientId &&
                parameters["redirect_uri"] == callbackUri &&
                parameters["state"] == state &&
                parameters["code_challenge"] == challenge &&
                parameters["code_challenge_method"] == "S256" &&
                parameters["scope"] == "offline_access"
        ) { "GitHub authorization parameters do not match this request." }
        return Start(authorization, Pending(connectionUid, brokerOrigin, callbackUri, clientId, state, verifier, started + lifetime * 1000))
    }

    /** Validate before consuming encrypted state. This has no network effect. */
    fun validateCallback(pending: Pending, callback: String): String {
        check(pending.brokerOrigin == brokerOrigin && pending.callbackUri == callbackUri && pending.clientId == clientId) { "GitHub authorization configuration changed. Reconnect." }
        check(now() < pending.expiresAt) { "GitHub sign-in expired. Start again." }
        val actual = runCatching { URI(callback) }.getOrNull() ?: error("Invalid GitHub callback.")
        val expected = URI(callbackUri)
        check(actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority && actual.rawPath == expected.rawPath && actual.fragment == null && actual.userInfo == null) { "Unexpected GitHub callback." }
        val values = parameters(actual.rawQuery)
        check(MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), pending.state.toByteArray())) { "GitHub authorization state did not match." }
        if ("error" in values) throw ConsentDenied()
        check(values.keys == setOf("code", "state")) { "Invalid GitHub authorization callback." }
        val code = values.getValue("code")
        check(code.matches(Regex("[A-Za-z0-9_-]{1,1024}"))) { "Invalid GitHub authorization code." }
        return code
    }

    /** Consume the encrypted pending transaction before dispatching this exchange. */
    suspend fun complete(pending: Pending, callback: String): GitHubOAuthCredential {
        val code = validateCallback(pending, callback)
        val started = now()
        val response = post(
            "exchange",
            buildJsonObject {
                put("state", pending.state)
                put("codeVerifier", pending.verifier)
                put("code", code)
            }
        )
        return credential(response, started)
    }

    suspend fun rotate(operationId: String, refreshToken: String): GitHubOAuthCredential {
        require(operationId.matches(Regex("[a-f0-9-]{36}")) && validToken(refreshToken)) { "Invalid GitHub credential rotation request." }
        val started = now()
        val response = post(
            "refresh",
            buildJsonObject {
                put("operationId", operationId)
                put("refreshToken", refreshToken)
            }
        )
        val credential = credential(response, started)
        check(credential.refreshToken != null && credential.refreshToken != refreshToken) { "GitHub refresh could not be verified. Reconnect GitHub." }
        return credential
    }

    private fun credential(response: JsonObject, started: Long): GitHubOAuthCredential = decodeGitHubOAuthCredential(response, started)

    private suspend fun post(operation: String, arguments: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        withTimeout(15_000) {
            val response = client.post("${brokerOrigin.trimEnd('/')}/v1/github/oauth/$operation") {
                contentType(ContentType.Application.Json)
                setBody(arguments.toString())
            }
            check(response.status.value in 200..299) { "GitHub sign-in could not complete (HTTP ${response.status.value}). Start sign-in again." }
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) {
                    yield()
                    continue
                }
                check(output.size() + read <= 32_768) { "GitHub sign-in returned an oversized response." }
                output.write(buffer, 0, read)
            }
            buffer.fill(0)
            val body = output.toString("UTF-8")
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: error("GitHub sign-in returned invalid data.")
        }
    }

    override fun close() = client.close()

    class ConsentDenied : IllegalStateException("GitHub authorization was declined or canceled.")

    companion object {
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        private fun validToken(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }
        private fun requireHttps(value: String, originOnly: Boolean = false) {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.query == null && uri.fragment == null && (!originOnly || uri.path.isNullOrEmpty() || uri.path == "/")) { "GitHub public authorization requires a fixed HTTPS configuration." }
        }
        private fun parameters(query: String?): Map<String, String> {
            require(query != null && query.length <= 8192) { "Invalid GitHub authorization parameters." }
            val result = linkedMapOf<String, String>()
            query.split('&').forEach { pair ->
                val parts = pair.split('=', limit = 2)
                require(parts.size == 2) { "Invalid GitHub authorization parameters." }
                fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: error("Invalid GitHub authorization encoding.")
                val key = decode(parts[0])
                require(key !in result) { "Duplicate GitHub authorization parameter." }
                result[key] = decode(parts[1])
            }
            return result
        }
        private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.longOrNull
    }
}
