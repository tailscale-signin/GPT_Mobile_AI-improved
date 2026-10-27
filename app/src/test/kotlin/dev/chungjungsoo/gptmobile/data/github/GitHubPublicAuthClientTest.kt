package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.URLEncoder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPublicAuthClientTest {
    private val origin = "https://auth.example.org"
    private val callback = "$origin/oauth/github/callback"
    private val state = "s".repeat(43)
    private val verifier = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val challenge = GitHubPublicAuthClient.challenge(verifier)
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun startResponse(host: String = "github.com"): String {
        val params = mapOf("client_id" to "Iv1.project", "redirect_uri" to callback, "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256", "scope" to "offline_access")
        val uri = "https://$host/login/oauth/authorize?" + params.entries.joinToString("&") { encoded(it.key) + "=" + encoded(it.value) }
        return buildJsonObject {
            put("state", state)
            put("redirectUri", callback)
            put("expiresIn", 600)
            put("authorizationUrl", uri)
        }.toString()
    }

    @Test fun pkceCodeExchangeNeverSendsClientSecretsOrCredentialsInUrls() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertFalse(body.contains("client_secret"))
                assertFalse(request.url.toString().contains(verifier))
                assertFalse(request.url.toString().contains(state))
                val value = Json.parseToJsonElement(body) as JsonObject
                if (requests == 1) {
                    assertEquals(challenge, value.getValue("codeChallenge").jsonPrimitive.content)
                    respond(startResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    assertEquals(verifier, value.getValue("codeVerifier").jsonPrimitive.content)
                    assertEquals(state, value.getValue("state").jsonPrimitive.content)
                    respond("""{"access_token":"ghu_private","refresh_token":"ghr_private","token_type":"bearer","expires_in":28800,"refresh_token_expires_in":15897600}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        )
        try {
            GitHubPublicAuthClient(http, origin, callback, "Iv1.project", { 1000 }, { ByteArray(it) }).use { client ->
                val start = client.begin("account-connection")
                assertFalse(start.toString().contains(state))
                assertFalse(start.pending.toString().contains(verifier))
                val token = client.complete(start.pending, "$callback?code=private-code&state=$state")
                assertEquals("ghu_private", token.accessToken)
                assertEquals(28_801_000L, token.expiresAt)
                assertFalse(token.toString().contains("ghu_private"))
                assertFalse(token.toString().contains("ghr_private"))
                assertEquals(2, requests)
            }
        } finally {
            http.close()
        }
    }

    @Test fun wrongStateDuplicateParametersWrongHostAndExpiredCallbacksNeverExchange() = runTest {
        var calls = 0
        var now = 1000L
        val http = HttpClient(
            MockEngine {
                calls++
                respond(startResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            GitHubPublicAuthClient(http, origin, callback, "Iv1.project", { now }, { ByteArray(it) }).use { client ->
                val pending = client.begin("connection").pending
                for (uri in listOf("$callback?code=secret-code&state=wrong", "$callback?code=secret-code&state=$state&state=$state", "https://attacker.example.org/oauth/github/callback?code=secret-code&state=$state", "$callback?code=secret-code&state=$state#fragment", "$callback?code=%ZZ&state=$state")) {
                    val failure = runCatching { client.complete(pending, uri) }.exceptionOrNull()
                    assertTrue(failure != null)
                    assertFalse(failure?.message.orEmpty().contains("secret-code"))
                }
                now = pending.expiresAt
                assertTrue(runCatching { client.complete(pending, "$callback?code=code&state=$state") }.isFailure)
                assertEquals(1, calls)
            }
        } finally {
            http.close()
        }
    }

    @Test fun hostileAuthorizationUrlsAndRedirectsNeverLaunchOrReceiveCodes() = runTest {
        for (redirect in listOf(false, true)) {
            var calls = 0
            val http = HttpClient(
                MockEngine {
                    calls++
                    if (redirect) respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://attacker.example.org")) else respond(startResponse("attacker.example.org"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
            try {
                GitHubPublicAuthClient(http, origin, callback, "Iv1.project", { 1000 }, { ByteArray(it) }).use { client ->
                    assertTrue(runCatching { client.begin("connection") }.isFailure)
                    assertEquals(1, calls)
                }
            } finally {
                http.close()
            }
        }
    }

    @Test fun declinedConsentAndProviderErrorsNeverExposeRemoteDescriptions() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine {
                calls++
                respond(if (calls == 1) startResponse() else "secret-upstream-body", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        // A successful start followed by user denial never reaches the exchange endpoint.
        val successful = HttpClient(MockEngine { respond(startResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            GitHubPublicAuthClient(successful, origin, callback, "Iv1.project", { 1000 }, { ByteArray(it) }).use { client ->
                val pending = client.begin("connection").pending
                val failure = runCatching { client.complete(pending, "$callback?state=$state&error=access_denied&error_description=secret-upstream-body") }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("declined"))
                assertFalse(failure?.message.orEmpty().contains("secret-upstream-body"))
            }
            GitHubPublicAuthClient(http, origin, callback, "Iv1.project").use { client ->
                val failure = runCatching { client.begin("connection") }.exceptionOrNull()
                assertTrue(failure != null)
                assertFalse(failure?.message.orEmpty().contains("secret-upstream-body"))
            }
        } finally {
            http.close()
            successful.close()
        }
    }
}
