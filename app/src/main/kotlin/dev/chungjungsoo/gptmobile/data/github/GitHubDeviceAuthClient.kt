package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** GitHub App user OAuth via device authorization. No app secret or private key belongs on the phone. */
internal class GitHubDeviceAuthClient(
    private val client: HttpClient,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val wallMillis: () -> Long = System::currentTimeMillis
) {
    class Authorization internal constructor(
        internal val clientId: String,
        internal val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        internal val expiresAt: Long,
        internal val intervalMillis: Long
    ) {
        override fun toString() = "GitHub device authorization (credentials redacted)"
    }

    suspend fun begin(clientId: String): Authorization {
        require(validClientId(clientId)) { "Enter the public client ID of your GitHub App with device flow enabled." }
        val started = elapsedMillis()
        val response = post(DEVICE_ENDPOINT, Parameters.build { append("client_id", clientId) })
        checkError(response)
        val uri = response.text("verification_uri")
        requireTrustedVerificationUri(uri)
        val lifetime = response.number("expires_in")?.takeIf { it in 1..1800 } ?: error("GitHub returned an invalid authorization lifetime.")
        val interval = response.number("interval")?.takeIf { it in 1..300 } ?: 5L
        val device = response.text("device_code").takeIf { it.length in 1..1024 && it.none(Char::isWhitespace) } ?: error("GitHub did not return a device authorization code.")
        val user = response.text("user_code").takeIf { it.matches(Regex("[A-Za-z0-9-]{4,64}")) } ?: error("GitHub did not return a user authorization code.")
        return Authorization(clientId, device, user, uri, started + lifetime * 1000L, interval * 1000L)
    }

    /** Polling waits before every attempt and slows down cumulatively. Cancellation never saves a token. */
    suspend fun awaitToken(authorization: Authorization): String = awaitCredential(authorization).accessToken

    suspend fun awaitCredential(authorization: Authorization): GitHubOAuthCredential {
        var interval = authorization.intervalMillis
        while (elapsedMillis() < authorization.expiresAt) {
            pause(minOf(interval, (authorization.expiresAt - elapsedMillis()).coerceAtLeast(0)))
            check(elapsedMillis() < authorization.expiresAt) { "GitHub sign-in expired. Start sign-in again." }
            val started = wallMillis()
            val response = post(
                TOKEN_ENDPOINT,
                Parameters.build {
                    append("client_id", authorization.clientId)
                    append("device_code", authorization.deviceCode)
                    append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                }
            )
            check(elapsedMillis() < authorization.expiresAt) { "GitHub sign-in expired. Start sign-in again." }
            when (response.text("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval += 5000L
                    continue
                }
                "" -> {
                    check(response.text("token_type").equals("bearer", true)) { "GitHub returned an unsupported token type." }
                    return decodeGitHubOAuthCredential(response, started)
                }
                else -> checkError(response)
            }
        }
        error("GitHub sign-in expired. Start sign-in again.")
    }

    /** Only credentials issued through device authorization may use this secret-free exchange. */
    suspend fun refresh(clientId: String, refreshToken: String): GitHubOAuthCredential {
        require(validClientId(clientId)) { "GitHub App client ID is invalid. Reconnect GitHub." }
        require(refreshToken.length in 1..8192 && refreshToken.none(Char::isWhitespace)) { "GitHub refresh credential is invalid. Reconnect GitHub." }
        val started = wallMillis()
        val response = post(
            TOKEN_ENDPOINT,
            Parameters.build {
                append("client_id", clientId)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }
        )
        checkError(response)
        check(response.text("token_type").equals("bearer", true)) { "GitHub returned an unsupported token type." }
        val rotated = decodeGitHubOAuthCredential(response, started)
        check(rotated.refreshToken != null && rotated.refreshToken != refreshToken) { "GitHub did not rotate the refresh credential. Reconnect GitHub." }
        return rotated
    }

    private suspend fun post(endpoint: String, parameters: Parameters): JsonObject = withContext(Dispatchers.IO) {
        withTimeout(15_000L) {
            val response = client.submitForm(endpoint, parameters) { header(HttpHeaders.Accept, "application/json") }
            check(response.status.value in 200..299) { "GitHub sign-in request failed (HTTP ${response.status.value}). Try again later." }
            val body = response.bodyAsText()
            check(body.length <= 32_768) { "GitHub sign-in returned an oversized response." }
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: error("GitHub sign-in returned an invalid response.")
        }
    }

    private fun checkError(response: JsonObject) {
        val message = when (response.text("error")) {
            "" -> return
            "access_denied" -> "GitHub sign-in was declined."
            "expired_token" -> "GitHub sign-in expired. Start sign-in again."
            "device_flow_disabled" -> "Enable device flow in your GitHub App registration."
            "incorrect_client_credentials" -> "GitHub did not recognize this client ID. Check the GitHub App registration."
            else -> "GitHub sign-in could not complete. Start sign-in again."
        }
        error(message)
    }

    companion object {
        const val DEVICE_ENDPOINT = "https://github.com/login/device/code"
        const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
        fun validClientId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))
        fun requireTrustedVerificationUri(value: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && uri.scheme == "https" && uri.host == "github.com" && uri.path == "/login/device" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null) { "GitHub returned an unexpected verification address." }
        }
        private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.longOrNull
    }
}
