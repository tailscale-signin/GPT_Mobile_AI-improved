package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchRequestItem
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import dev.chungjungsoo.gptmobile.domain.service.OpenRouterBatchQueueManager
import dev.chungjungsoo.gptmobile.domain.service.RetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterBatchPolicyTest {
    @Test fun queuedCredentialsNeverGoToAnUnapprovedOrigin() = runBlocking {
        val result = runCatching {
            OpenRouterBatchClient("fixture", baseUrl = "http://openrouter.invalid/api/v1").processRequest(BatchRequest("fixture", model = "fixture/model"))
        }
        assertTrue(result.exceptionOrNull() is dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException)
    }

    @Test fun localBatchFailureStaysOnTheLocalConnection() = runBlocking {
        var results = emptyList<dev.chungjungsoo.gptmobile.domain.service.BatchResult>()
        dev.chungjungsoo.gptmobile.data.llama.LlamaBatchClient(serverUrl = "invalid-url").processBatch(listOf(BatchRequest("fixture"))) { results = it }
        assertEquals("Local batch failed. Retry with the selected local connection.", (results.single() as dev.chungjungsoo.gptmobile.domain.service.BatchResult.Failure).error)
    }

    @Test fun nativePolicyPrecedesRequestsAndHardPrivacyCannotDisappear() {
        val requests = listOf(NativeBatchRequest("fixture", JSONObject().put("messages", org.json.JSONArray())))
        val client = OpenRouterBatchClient("fixture", policy = OpenRouterProviderRouting(only = listOf("provider-fixture"), allowFallbacks = false))
        val payload = client.buildBatchPayload("/v1/chat/completions", "fixture/model", requests)
        assertTrue(payload.indexOf("\"provider\"") < payload.indexOf("\"requests\""))
        assertEquals("provider-fixture", JSONObject(payload).getJSONObject("provider").getJSONArray("only").getString(0))
        assertThrows(IllegalArgumentException::class.java) { OpenRouterBatchClient("fixture", policy = OpenRouterProviderRouting(zdr = true)).buildBatchPayload("/v1/chat/completions", "fixture/model", requests) }
    }

    @Test fun timerFlushDoesNotCancelItsOwnSuspendingDispatch() = runTest {
        var sent = false
        val queue = OpenRouterBatchQueueManager(OpenRouterSettings(apiKey = "", flushTimeoutMs = 1000), this) {
            delay(10)
            sent = true
        }
        queue.enqueue(OpenRouterBatchRequestItem("fixture", "fixture/model", emptyList()))
        advanceUntilIdle()
        assertTrue(sent)
        assertEquals(0, queue.pendingCount())
    }

    @Test fun zeroRetriesStillMakesTheInitialAttempt() = runBlocking {
        var attempts = 0
        val result = RetryPolicy(maxRetries = 0).executeWithRetry {
            attempts++
            Result.success("fixture")
        }
        assertEquals(1, attempts)
        assertEquals("fixture", result.getOrThrow())
    }
}
