package dev.chungjungsoo.gptmobile.data.agent.tool

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringApiFailureTest {
    @Test fun secondaryRateLimitBodyKeepsNativeWatcherRecoverableWithoutLeakingBodyText() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("api.github.com", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("X-RateLimit-Remaining", "4999")
                .body("""{"message":"You have exceeded a secondary rate limit","private":"sensitive-marker"}""".toResponseBody()).build()
        }.build()
        try {
            val error = runCatching { GitHubEngineeringApi(client) { "token" }.request("/repos/o/r/pulls/42") }.exceptionOrNull() as EngineeringApiException
            assertEquals("GITHUB_SECONDARY_RATE_LIMITED", error.category)
            assertNotNull(error.retryAtEpochMs)
            assertTrue(error.retryAtEpochMs!! >= System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(55))
            assertFalse(error.message.orEmpty().contains("sensitive-marker"))
            assertEquals(1, calls)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun permissionDeficitDoesNotUseAnOrdinaryQuotaResetAsRetryAuthorization() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("X-RateLimit-Remaining", "4999").header("X-RateLimit-Reset", "9999999999")
                .body("""{"message":"Resource not accessible by integration"}""".toResponseBody()).build()
        }.build()
        try {
            val error = runCatching { GitHubEngineeringApi(client) { "token" }.request("/repos/o/r/pulls/42") }.exceptionOrNull() as EngineeringApiException
            assertEquals("GITHUB_ENDPOINT_PERMISSION_REQUIRED", error.category)
            assertNull(error.retryAtEpochMs)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
