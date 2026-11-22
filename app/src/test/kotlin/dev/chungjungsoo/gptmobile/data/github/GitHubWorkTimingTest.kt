package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkTimingTest {
    private fun span(start: Long, end: Long) = GitHubTimingInterval(start, end)

    @Test fun nestedWorkAndWaitsAreNonOverlappingAndBounded() {
        val result = requireNotNull(
            GitHubWorkTiming.project(
                0,
                1000,
                active = listOf(span(100, 800), span(200, 400), span(100, 800)),
                providerWait = listOf(span(450, 700)),
                userWait = listOf(span(300, 500))
            )
        )
        assertEquals(300L, result.observedActiveMillis)
        assertEquals(200L, result.providerWaitMillis)
        assertEquals(200L, result.userWaitMillis)
        assertEquals(300L, result.unobservedMillis)
        assertEquals(400L, result.overlappingEvidenceMillis)
        assertEquals(result.elapsedMillis, result.observedActiveMillis + result.providerWaitMillis + result.userWaitMillis + result.unobservedMillis)
        assertFalse(result.incompleteEvidence)
    }

    @Test fun parallelRunsContributeIntervalUnionInsteadOfSummedDurations() {
        val runA = requireNotNull(GitHubWorkTiming.project(100, 800, active = listOf(span(100, 600))))
        val runB = requireNotNull(GitHubWorkTiming.project(300, 900, active = listOf(span(300, 800))))
        val task = requireNotNull(GitHubWorkTiming.project(100, 1000, active = listOf(span(100, 600), span(300, 800))))
        assertEquals(1000L, runA.observedActiveMillis + runB.observedActiveMillis)
        assertEquals(700L, task.observedActiveMillis)
        assertEquals(200L, task.unobservedMillis)
    }

    @Test fun missingIntervalHistoryDoesNotTurnCounterTotalsIntoExactSlices() {
        val older = requireNotNull(GitHubWorkTiming.project(0, 1000, unlocatedProviderWaitMillis = 500))
        assertEquals(0L, older.providerWaitMillis)
        assertEquals(1000L, older.unobservedMillis)
        assertEquals(500L, older.unlocatedProviderWaitMillis)
        assertTrue(older.incompleteEvidence)
        assertEquals(JsonPrimitive(false), older.json()["unlocated_provider_wait_in_elapsed_breakdown"])
        assertEquals(JsonPrimitive(false), older.json()["unobserved_is_suspended"])
        val unknown = requireNotNull(GitHubWorkTiming.project(0, 1000, unlocatedProviderWaitMillis = null))
        assertEquals(JsonNull, unknown.json()["unlocated_provider_wait_millis"])
        assertTrue(unknown.incompleteEvidence)
    }

    @Test fun clippingMalformedTimesAndOverflowDoNotInventWork() {
        val result = requireNotNull(GitHubWorkTiming.project(100, 200, active = listOf(span(0, 150), span(180, 1000), span(170, 160), span(-1, 190))))
        assertEquals(70L, result.observedActiveMillis)
        assertEquals(30L, result.unobservedMillis)
        assertTrue(result.incompleteEvidence)
        assertNull(GitHubWorkTiming.project(-1, 100))
        assertNull(GitHubWorkTiming.project(200, 100))
        assertNull(GitHubTimingInterval.duration(Long.MAX_VALUE - 1, 2))
        assertNull(GitHubTimingInterval.millis(Long.MAX_VALUE))
        assertNull(GitHubTimingInterval.seconds(-1, 0))
        assertNotNull(GitHubTimingInterval.duration(Long.MAX_VALUE - 1, 1))
        val long = requireNotNull(GitHubWorkTiming.project(0, Long.MAX_VALUE, active = listOf(span(0, Long.MAX_VALUE))))
        assertEquals(Long.MAX_VALUE, long.observedActiveMillis)
        assertEquals(0L, long.unobservedMillis)
    }

    @Test fun emptyEvidenceRemainsUnobservedAndZeroWindowRemainsZero() {
        val unknown = requireNotNull(GitHubWorkTiming.project(100, 200))
        assertEquals(100L, unknown.unobservedMillis)
        assertEquals(0L, unknown.observedActiveMillis)
        assertTrue(unknown.description().contains("unobserved"))
        val empty = requireNotNull(GitHubWorkTiming.project(100, 100, active = listOf(span(0, 200))))
        assertEquals(0L, empty.elapsedMillis)
        assertEquals(0L, empty.observedActiveMillis)
        assertEquals(0L, empty.unobservedMillis)
    }

    @Test fun deterministicOverlappingSchedulesMatchPerMillisecondClassification() {
        val random = java.util.Random(917)
        repeat(200) {
            fun observations() = List(12) {
                val from = random.nextInt(100).toLong()
                span(from, from + random.nextInt(40))
            }
            val active = observations()
            val provider = observations()
            val user = observations()
            val result = requireNotNull(GitHubWorkTiming.project(10, 110, active, provider, user))
            val expected = LongArray(4)
            for (at in 10L until 110L) {
                fun covers(rows: List<GitHubTimingInterval>) = rows.any { at >= it.startMillis && at < it.endMillis }
                expected[
                    when {
                        covers(user) -> 0
                        covers(provider) -> 1
                        covers(active) -> 2
                        else -> 3
                    }
                ]++
            }
            assertEquals(expected.toList(), listOf(result.userWaitMillis, result.providerWaitMillis, result.observedActiveMillis, result.unobservedMillis))
        }
    }
}
