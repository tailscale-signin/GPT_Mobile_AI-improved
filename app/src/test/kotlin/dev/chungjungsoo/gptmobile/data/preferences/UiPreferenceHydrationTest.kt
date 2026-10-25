package dev.chungjungsoo.gptmobile.data.preferences

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionCoordinator
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardRepository
import dev.chungjungsoo.gptmobile.data.chat.PinnedConversationOrderStore
import dev.chungjungsoo.gptmobile.data.pricing.ProviderPricingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UiPreferenceHydrationTest {
    private class CheckedContext(context: Context) : ContextWrapper(context) {
        private val constructingThread = Thread.currentThread()
        val reads = CopyOnWriteArrayList<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(Thread.currentThread() !== constructingThread) { "UI preference access: $name" }
            reads += name
            return super.getSharedPreferences(name, mode)
        }
    }

    @Test fun constructorsDoNotReadPreferencesAndHydrationMovesColdReadsOffTheCaller() = runBlocking {
        val base: Application = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("pinned_conversation_order", Context.MODE_PRIVATE).edit().putString("order", "8,3").commit()
        val context = CheckedContext(base)
        val pins = PinnedConversationOrderStore(context)
        val pricing = ProviderPricingRepository(context, mockk(relaxed = true))
        val scoreboard = ScoreboardRepository(context, mockk(relaxed = true), mockk(relaxed = true))
        assertTrue(context.reads.isEmpty())
        // An action before hydration must preserve existing order instead of overwriting it.
        assertEquals(listOf(8, 3, 9), pins.update { it + 9 })
        assertEquals(listOf(8, 3, 9), pins.order.value)
        pricing.hydrate()
        scoreboard.hydrate()
        assertTrue(context.reads.containsAll(listOf("pinned_conversation_order", "provider_pricing_v1", "community_scoreboard_v1")))
    }

    @Test fun benchmarkRecoveryReadsItsOwnershipJournalOnIo() = runBlocking {
        val context = CheckedContext(RuntimeEnvironment.getApplication())
        val recovered = CompletableDeferred<Unit>()
        val store = mockk<BenchmarkStore>(relaxed = true)
        every { store.batches } returns MutableStateFlow(emptyList())
        coEvery { store.recoverBatches() } coAnswers {
            recovered.complete(Unit)
            Unit
        }
        BenchmarkExecutionCoordinator(context, store)
        withTimeout(5000) { recovered.await() }
        assertTrue("benchmark_execution_v1" in context.reads)
    }
}
