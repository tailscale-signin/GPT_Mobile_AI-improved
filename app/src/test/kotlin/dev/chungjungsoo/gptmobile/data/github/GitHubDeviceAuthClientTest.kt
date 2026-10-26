package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDeviceAuthClientTest {
    private val code = """{"device_code":"private-device-code","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
    private val token = """{"access_token":"test-user-token","token_type":"bearer"}"""

    @Test fun pendingAndSlowDownRespectThePollingInterval() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val requests = mutableListOf<String>()
        val responses = ArrayDeque(listOf(code, """{"error":"authorization_pending"}""", """{"error":"slow_down"}""", token))
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.toString()
                assertEquals("application/json", request.headers[HttpHeaders.Accept])
                assertFalse(request.url.toString().contains("private-device-code"))
                assertFalse(request.url.toString().contains("client_secret"))
                val form = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertFalse(form.contains("client_secret"))
                assertTrue(form.contains("client_id=Iv1.public-client"))
                if (request.url.toString() == GitHubDeviceAuthClient.TOKEN_ENDPOINT) {
                    assertTrue(form.contains("device_code=private-device-code"))
                    assertTrue(form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
                }
                respond(responses.removeFirst(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val auth = GitHubDeviceAuthClient(client, { now }, {
                waits += it
                now += it
            })
            val authorization = auth.begin("Iv1.public-client")
            assertEquals("ABCD-EFGH", authorization.userCode)
            assertFalse(authorization.toString().contains("private-device-code"))
            assertEquals("test-user-token", auth.awaitToken(authorization))
            assertEquals(listOf(5000L, 5000L, 10000L), waits)
            assertEquals(GitHubDeviceAuthClient.DEVICE_ENDPOINT, requests.first())
            assertTrue(requests.drop(1).all { it == GitHubDeviceAuthClient.TOKEN_ENDPOINT })
        } finally {
            client.close()
        }
    }

    @Test fun declinedAuthorizationIsTerminalAndDoesNotExposeThePayload() = runTest {
        var calls = 0
        var now = 0L
        val client = HttpClient(
            MockEngine {
                calls++
                respond(if (calls == 1) code else """{"error":"access_denied","error_description":"secret-provider-body"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val auth = GitHubDeviceAuthClient(client, { now }, { now += it })
            val error = runCatching { auth.awaitToken(auth.begin("public-client")) }.exceptionOrNull()
            assertEquals("GitHub sign-in was declined.", error?.message)
            assertEquals(2, calls)
        } finally {
            client.close()
        }
    }

    @Test fun expiryAndCancellationPreventTokenPolling() = runTest {
        for (cancel in listOf(false, true)) {
            var calls = 0
            var now = 0L
            val client = HttpClient(
                MockEngine {
                    calls++
                    respond(code, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
            try {
                val auth = GitHubDeviceAuthClient(client, { now }, { if (cancel) throw CancellationException("closed") else now += 900_000L })
                val error = runCatching { auth.awaitToken(auth.begin("public-client")) }.exceptionOrNull()
                assertTrue(error != null)
                if (cancel) assertTrue(error is CancellationException)
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
    }

    @Test fun verificationUrisCannotRedirectTheSignInToAnotherOrigin() {
        GitHubDeviceAuthClient.requireTrustedVerificationUri("https://github.com/login/device")
        listOf("http://github.com/login/device", "https://github.com.evil.test/login/device", "https://evil.test/login/device", "https://github.com@evil.test/login/device", "https://github.com/login/device?next=https://evil.test", "https://github.com:8443/login/device").forEach {
            assertTrue(runCatching { GitHubDeviceAuthClient.requireTrustedVerificationUri(it) }.isFailure)
        }
    }

    @Test fun unknownProviderErrorAndMalformedResponsesNeverRevealRawSecrets() = runTest {
        for (body in listOf("secret-provider-body", """{"error":"unknown","error_description":"secret-provider-body"}""")) {
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                val error = runCatching { GitHubDeviceAuthClient(client).begin("public-client") }.exceptionOrNull()
                assertTrue(error != null)
                assertFalse(error?.message.orEmpty().contains("secret-provider-body"))
            } finally {
                client.close()
            }
        }
    }

    @Test fun invalidClientIdDoesNotSendAnAuthorizationRequest() = runTest {
        val client = HttpClient(MockEngine { error("Must not send") })
        try {
            assertTrue(runCatching { GitHubDeviceAuthClient(client).begin("invalid\r\nclient") }.isFailure)
        } finally {
            client.close()
        }
    }
}
