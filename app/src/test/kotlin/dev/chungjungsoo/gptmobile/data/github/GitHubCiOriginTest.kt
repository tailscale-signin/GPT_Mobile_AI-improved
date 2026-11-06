package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiOriginTest {
    @Test fun enterpriseLogRedirectRetainsTheApiOriginAndStripsStorageCredentials() = runTest {
        val seen = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                seen += request.url.toString()
                if (request.url.host == "git.example.org") {
                    assertEquals("/api/v3/repos/o/r/actions/jobs/7/logs", request.url.encodedPath)
                    assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://fixture.blob.core.windows.net/log.txt?sig=opaque"))
                } else {
                    assertEquals(null, request.headers[HttpHeaders.Authorization])
                    respond("job result fixture")
                }
            }
        ).use { client ->
            val reader = GitHubCiLogReader(client, "fixture", GitHubRequestScheduler(), GitHubResponseCache(), apiOrigins = GitHubApiOrigins.fromEndpoint("https://git.example.org"))
            assertEquals("job result fixture", reader.read("/repos/o/r/actions/jobs/7/logs", true).text)
            assertEquals(2, seen.size)
        }
    }

    @Test fun anotherGitHubApiHostCannotReceiveEnterpriseCredentials() = runTest {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://api.github.com/repos/o/r/actions/jobs/7/logs"))
            }
        ).use { client ->
            val reader = GitHubCiLogReader(client, "fixture", GitHubRequestScheduler(), GitHubResponseCache(), apiOrigins = GitHubApiOrigins.fromEndpoint("https://git.example.org"))
            assertTrue(runCatching { reader.read("/repos/o/r/actions/jobs/7/logs", false) }.isFailure)
            assertEquals(1, requests)
        }
    }
}
