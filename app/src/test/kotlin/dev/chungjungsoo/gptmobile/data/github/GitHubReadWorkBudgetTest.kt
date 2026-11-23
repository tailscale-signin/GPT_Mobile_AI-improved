package dev.chungjungsoo.gptmobile.data.github

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadWorkBudgetTest {
    @Test fun preparationAndDownloadsConsumeTheSameDeadline() = runBlocking {
        var clock = 0L
        val budget = GitHubReadWorkBudget(100) { clock }
        assertEquals(100L, budget.remainingMs)
        assertEquals(
            "prepared",
            budget.run {
                clock = 90_000_000
                "prepared"
            }
        )
        assertEquals(10L, budget.remainingMs)
        clock = 100_000_000
        assertNull(budget.run { error("Expired work must not dispatch") })
        assertTrue(budget.expired)
    }

    @Test fun localTimeoutReturnsPartialButExternalCancellationPropagates() = runBlocking {
        assertNull(GitHubReadWorkBudget(25).run { awaitCancellation() })
        val entered = CompletableDeferred<Unit>()
        var propagated = false
        val job = launch {
            try {
                GitHubReadWorkBudget(10_000).run {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (cancelled: CancellationException) {
                propagated = true
                throw cancelled
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(propagated)
    }

    @Test fun lexicalAndPhoneParsersCooperateInsideLongLinesAndComments() {
        val checks = AtomicInteger()
        val cancelled = CancellationException("cancel parse")
        val probe = { if (checks.incrementAndGet() >= 10) throw cancelled }
        for (parse in listOf<(() -> Unit) -> Unit>(
            { checkpoint -> GitHubLocalIndex.build("value ".repeat(100_000), checkpoint) },
            { checkpoint -> PhoneRepositorySourceIndex.build("Long.kt", "/*" + "comment ".repeat(100_000) + "*/", checkpoint) },
            { checkpoint -> PhoneRepositorySourceIndex.build("Long.py", "value = \"" + "quoted ".repeat(100_000) + "\"", checkpoint) },
            { checkpoint -> PhoneRepositorySourceIndex.build("Escaped.kt", "value = \"" + "\\q".repeat(300_000) + "\"", checkpoint) }
        )) {
            checks.set(0)
            assertEquals(cancelled, runCatching { parse(probe) }.exceptionOrNull())
            assertEquals(10, checks.get())
        }
        assertFalse(GitHubReadWorkBudget(100).expired)
    }
}
