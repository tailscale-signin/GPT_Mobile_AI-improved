package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterManagementBinding
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceipt
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptContext
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterWalletLocalStore
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterWalletLocalStoreTest {
    private val binding = OpenRouterManagementBinding("a", "protected-ref", "association-1", "https://openrouter.ai/api/v1", setOf("inference-1"))
    private fun receipt(id: String = "attempt-1", generation: String? = "gen-1") = OpenRouterReceipt(
        localId = id,
        context = OpenRouterReceiptContext("a", "inference-1", binding.endpoint, "profile", "model", "primary", accountBindingRevision = binding.revision),
        generationId = generation,
        remoteEvidence = true
    )

    private suspend fun store(preferences: InMemoryRouterPreferences = InMemoryRouterPreferences()) = OpenRouterWalletLocalStore(preferences).also { local ->
        local.update { it.copy(enabled = true, bindings = mapOf("a" to binding)) }
    }

    @Test fun concurrentDuplicateCallbacksClaimOnlyOneDispatchAndSeparateGenerationsRemainDistinct() = runTest {
        val local = store()
        val events = (1..20).map { async { local.accept(receipt()) } }.awaitAll().filterNotNull()
        assertEquals(1, events.size)
        assertNotNull(local.claim(events.single(), 1))
        assertNull(local.claim(events.single(), 1))
        assertNull(local.accept(receipt("another-callback", "gen-1")))
        assertNotNull(local.accept(receipt("attempt-2", "gen-2")))
    }

    @Test fun disableEnableDoesNotReleaseEarlierEventsAndNeverCreatesAnEvent() = runTest {
        val local = store()
        val event = requireNotNull(local.accept(receipt()))
        local.setEnabled(false)
        local.setEnabled(true)
        assertNull(local.claim(event, 1))
        assertEquals(1, local.snapshot().events.size)
        assertNotNull(local.accept(receipt("attempt-2", "gen-2")))
    }

    @Test fun restartAbandonsAcceptedAndClaimedEventsWithoutReplayingThem() = runTest {
        val preferences = InMemoryRouterPreferences()
        val local = store(preferences)
        val first = requireNotNull(local.accept(receipt()))
        local.claim(first, 1)
        local.accept(receipt("attempt-2", "gen-2"))
        val reopened = OpenRouterWalletLocalStore(preferences)
        reopened.abandonUnfinished()
        assertEquals(setOf("abandoned"), reopened.snapshot().events.values.map { it.state }.toSet())
        assertNull(reopened.accept(receipt()))
        assertNull(reopened.claim(first, 1))
    }

    @Test fun rotatedInferenceDetachesCachePreservesProtectedAccessAndRejectsLateResult() = runTest {
        val local = store()
        val observation = SettingsHomeWalletSnapshot("a", "Account", 1, "0.00000001", "1", "0.99999999", 10)
        local.publish(binding, observation, null)
        local.invalidateConnection("a")
        assertFalse(requireNotNull(local.snapshot().bindings["a"]).associated)
        assertEquals("protected-ref", local.snapshot().bindings["a"]?.secretRef)
        local.publish(binding, observation, null)
        assertNull(local.snapshot().observations["a"])
        assertNull(local.accept(receipt()))
        assertEquals("protected-ref", local.detach("a"))
        assertNull(local.snapshot().bindings["a"])
    }

    @Test fun failedRefreshKeepsExactSuccessfulAmountAndTimestampAndCooldownEventsAreNeverReplayed() = runTest {
        val local = store()
        val observation = SettingsHomeWalletSnapshot("a", "Account", 1, "0.00000001", "1", "0.99999999", 10)
        local.publish(binding, observation, null)
        local.publish(binding, null, AccountFailure.RATE_LIMITED, cooldownUntil = 100)
        assertEquals(observation, local.snapshot().observations["a"]?.snapshot)
        assertNull(local.accept(receipt(), now = 50))
        assertNull(local.accept(receipt(), now = 101))
        val next = requireNotNull(local.accept(receipt("attempt-2", "gen-2"), now = 101))
        assertNotNull(local.claim(next, 101))
    }

    @Test fun missingBindingAndOtherAccountCannotPopulateSelectedWallet() = runTest {
        val local = store()
        assertNull(local.accept(receipt().copy(context = receipt().context.copy(connectionUid = "b"))))
        assertNull(local.accept(receipt("attempt-2", "gen-2").copy(context = receipt().context.copy(accountBindingRevision = "old"))))
        local.publish(binding.copy(revision = "old"), SettingsHomeWalletSnapshot("a", "Account", 1, "10", "10", "0", 10), null)
        assertNull(local.snapshot().observations["a"])
    }
}
