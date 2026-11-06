package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubEngineeringOriginsTest {
    @Test fun enterpriseWriteAndItsAccountVerificationUseTheSameConfiguredOrigin() = runBlocking {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            assertEquals("Bearer enterprise-write-fixture", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":42}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = GitHubEngineeringApi(http) { "enterprise-write-fixture" }.configureOrigins(GitHubApiOrigins.fromEndpoint("https://git.example.org"))
        api.expectedWriteActor = "42"
        api.request("/repos/o/r/issues", "POST", JSONObject().put("title", "fixture"))
        assertEquals(listOf("https://git.example.org/api/v3/user", "https://git.example.org/api/v3/repos/o/r/issues"), requests)
    }

    @Test fun orphanedEngineeringValidatorRetriesExactlyOnceAndCannotLoop() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals(null, chain.request().header("If-None-Match"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(304).message("Not Modified")
                .body("".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = GitHubEngineeringApi(http) { "orphan-fixture" }
        assertTrue(runCatching { api.request("/user") }.isFailure)
        assertEquals(2, requests)
        assertTrue(!GitHubSessionCaches.authenticationForCredential("orphan-fixture").get())
    }
}
