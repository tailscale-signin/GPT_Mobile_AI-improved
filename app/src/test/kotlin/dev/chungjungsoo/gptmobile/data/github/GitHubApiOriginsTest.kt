package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiOriginsTest {
    @Test fun anOrphaned304TriggersOneUnconditionalRead() = runTest {
        var requests = 0
        HttpClient(
            MockEngine { request ->
                requests++
                assertEquals(null, request.headers[HttpHeaders.IfNoneMatch])
                if (requests == 1) respond("", HttpStatusCode.NotModified) else respond("""{"id":7,"login":"verified"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ).use { http ->
            val result = GitHubWorkspaceClient("credential", http).execute("get_account", buildJsonObject {}).jsonObject
            assertEquals("7", result["id"]?.jsonPrimitive?.content)
            assertEquals(2, requests)
        }
    }

    @Test fun aRepeatedOrphaned304StopsWithoutAnInfiniteRetry() = runTest {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("", HttpStatusCode.NotModified)
            }
        ).use { http ->
            assertTrue(runCatching { GitHubWorkspaceClient("credential", http).execute("get_account", buildJsonObject {}) }.isFailure)
            assertEquals(2, requests)
        }
    }

    @Test fun accountReadsUseTheConfiguredHostAndCannotReuseAnotherHostsCache() = runTest {
        val hosts = mutableListOf<String>()
        val cache = GitHubResponseCache()
        HttpClient(
            MockEngine { request ->
                hosts += request.url.host
                assertEquals("Bearer credential", request.headers[HttpHeaders.Authorization])
                assertEquals(null, request.headers[HttpHeaders.IfNoneMatch])
                respond("""{"id":7,"login":"verified"}""", headers = headersOf(HttpHeaders.ETag, "\"tag\""))
            }
        ).use { http ->
            GitHubWorkspaceClient("credential", http, responseCache = cache).execute("get_account", buildJsonObject {})
            GitHubWorkspaceClient("credential", http, responseCache = cache, apiOrigins = GitHubApiOrigins.fromEndpoint("https://git.example.org")).execute("get_account", buildJsonObject {})
        }
        assertEquals(listOf("api.github.com", "git.example.org"), hosts)
    }

    @Test fun concurrentMutableSafeReadsShareOnePhysicalRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                started.complete(Unit)
                release.await()
                respond("""{"id":7,"login":"verified"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ).use { http ->
            val client = GitHubWorkspaceClient("credential", http)
            val first = async { client.execute("get_account", buildJsonObject {}) }
            started.await()
            val second = async { client.execute("get_account", buildJsonObject {}) }
            testScheduler.runCurrent()
            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, requests)
        }
    }
}
