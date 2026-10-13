package dev.chungjungsoo.gptmobile.presentation.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PickerRefreshLifecycleTest {
    @Test fun scoreboardFailureDoesNotBlockPrices() = runTest {
        var refreshed = false
        refreshInsightSources(scores = { error("offline") }, prices = { refreshed = true })
        assertTrue(refreshed)
    }

    @Test fun slowScoreboardDoesNotBlockPricesAndDismissCancelsBoth() = runTest {
        val priceStarted = CompletableDeferred<Unit>()
        var scoreboardStopped = false
        var pricesStopped = false
        val job = launch {
            refreshInsightSources(
                scores = {
                    try {
                        awaitCancellation()
                    } finally {
                        scoreboardStopped = true
                    }
                },
                prices = {
                    try {
                        priceStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        pricesStopped = true
                    }
                }
            )
        }
        runCurrent()
        assertTrue(priceStarted.isCompleted)
        job.cancelAndJoin()
        assertTrue(scoreboardStopped && pricesStopped)
    }
}
