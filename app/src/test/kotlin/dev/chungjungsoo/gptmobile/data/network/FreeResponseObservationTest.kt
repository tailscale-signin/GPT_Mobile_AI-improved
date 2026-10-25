package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeResponseObservationTest {
    @Test fun sameUnusableCachedResultIsRecognizedWithoutRetainingPrivateContent() {
        var clock = 1000L
        val cache = UnusableResponseCache { clock }
        val headers = mapOf("X-Cache" to "HIT", "ETag" to "private-cache-key", "Date" to "Sun, 25 Oct 2026 02:00:00 GMT", "Age" to "42")
        val observation = FreeResponseObservation.fromHeaders("json") { headers[it] }
        val first = cache.observe("request-digest", "private reasoning", observation, false)
        assertFalse(first.repeatedUnusableCache)
        val repeated = cache.observe("request-digest", "private reasoning", observation, false)
        assertTrue(repeated.repeatedUnusableCache)
        assertEquals(42L, repeated.cacheAgeSeconds)
        assertNotEquals("private-cache-key", repeated.cacheIdentityDigest)
        assertFalse(Json.encodeToString(repeated).contains("private"))
        assertFalse(cache.observe("different-request", "private reasoning", observation, false).repeatedUnusableCache)
        clock += 300_001
        assertFalse(cache.observe("request-digest", "private reasoning", observation, false).repeatedUnusableCache)
    }

    @Test fun successfulAnswerClearsTheBadCacheObservationAndShapeContainsOnlyCounts() {
        val cache = UnusableResponseCache { 1000L }
        val shape = FreeResponseObservation("json", cacheStatus = "HIT")
        cache.observe("request", "empty", shape, false)
        cache.observe("request", "answer", shape, true)
        assertFalse(cache.observe("request", "empty", shape, false).repeatedUnusableCache)
        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>("""{"model":"route/model","choices":[{"message":{"content":"secret answer","reasoning":"secret thinking"},"finish_reason":"stop"}]}""")
        val counted = shape.withChunk(chunk)
        assertEquals(13, counted.visibleCharacters)
        assertEquals(15, counted.reasoningCharacters)
        assertEquals(listOf("stop"), counted.finishReasons)
        assertFalse(Json.encodeToString(counted).contains("secret"))
    }
}
