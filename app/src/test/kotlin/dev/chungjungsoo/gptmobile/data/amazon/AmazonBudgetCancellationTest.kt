package dev.chungjungsoo.gptmobile.data.amazon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonBudgetCancellationTest {
    @Test fun cancellationAfterChallengeDetectionStillCommitsCooldownWithoutMoreIo() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var state = AmazonBudgetState()
        val store = object : AmazonBudgetStore {
            override suspend fun read() = state
            override suspend fun update(change: (AmazonBudgetState) -> AmazonBudgetState): AmazonBudgetState {
                val next = change(state)
                if (next.marketplaceCooldowns.isNotEmpty()) {
                    started.complete(Unit)
                    finish.await()
                }
                state = next
                return next
            }
        }
        val clock = AmazonMutableClock()
        var calls = 0
        val budget = AmazonRequestBudget(store, clock)
        val request = async {
            budget.request(AmazonFreeMarket.CANADA, 10, { true }) {
                calls++
                throw AmazonReadException(AmazonReadError.CHALLENGE_REQUIRED, "Blocked")
            }
        }
        started.await()
        request.cancel()
        finish.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, calls)
        assertEquals(1, state.usageCount)
        assertEquals(clock.millis() + 86_400_000, state.marketplaceCooldowns["amazon.ca"])
    }
}
