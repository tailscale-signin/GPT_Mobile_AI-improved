package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOutputAllocationTest {
    @Test fun audited8192ContextNeverReservesAllTokensForOutput() {
        val output = LocalOutputAllocation.reserve(8192, 851, 256, 8192)
        assertEquals(4096, output)
        assertTrue(851L + 256 + output <= 8192)
        val plan = LocalContextPlanner.plan(emptyList(), "x".repeat(1702), "", emptyList(), 8192, 8192)
        assertEquals(4096, plan.outputReserveTokens)
    }

    @Test fun boundaryAndHugeCountsNeverOverflow() {
        assertEquals(1, LocalOutputAllocation.reserve(1024, 767, 256, 8192))
        assertThrows(IllegalArgumentException::class.java) { LocalOutputAllocation.reserve(1024, 768, 256, 8192) }
        assertThrows(IllegalArgumentException::class.java) { LocalOutputAllocation.reserve(8192, Long.MAX_VALUE, 256, 8192) }
        assertThrows(IllegalArgumentException::class.java) { LocalOutputAllocation.reserve(0, 1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { LocalOutputAllocation.reserve(1, Long.MAX_VALUE, Int.MAX_VALUE, 8192) }
    }

    @Test fun replayPreservesLargeUsableContextButDistrustsAdvertisedHugeLimits() {
        assertTrue(ReplayAllowance.tokens(32768, 5000, 4096, 4000) > 4000)
        assertEquals(ReplayAllowance.tokens(32768, 5000, 4096, 4000), ReplayAllowance.tokens(Int.MAX_VALUE, 5000, 4096, 4000))
    }

    @Test fun toolStreamAcceptsBoundedOverflowAndRejectsBeyondTheBuffer() {
        val guard = dev.chungjungsoo.gptmobile.data.agent.ToolStreamGuard()
        repeat(128) { guard.slot("call$it") }
        // Repeated fragments for an admitted call must not consume another slot.
        guard.slot("call0")
        val failure = assertThrows(dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException::class.java) { guard.slot("call128") }
        assertEquals("STREAM_TOOL_SLOTS", failure.threshold)
        assertEquals(129, failure.observedValue)
        assertEquals(128, failure.allowedValue)
    }

    @Test fun continuationManifestIsBoundedAndKeepsLastCommittedTextAndReader() {
        val text = dev.chungjungsoo.gptmobile.data.agent.recoveryManifest("run:generation", "Old text ".repeat(10000) + "Last committed finding.", listOf("Research complete=false; partial coverage"), listOf("recovery://1/run"), 500000, 6000)
        assertTrue(text.length <= 6000)
        assertTrue(text.contains("Last committed finding."))
        assertTrue(text.contains("read_recovery_context at offset=0"))
        assertTrue(text.contains("unknown outcome"))
        assertTrue(!text.contains("Old text ".repeat(1000)))
    }

    @Test fun policyRebuildHasThreePreDispatchAttemptsAndNeverReplaysSideEffects() {
        val fence = PreparationReplayFence()
        val stale = IllegalStateException("DISPATCH_POLICY_CHANGED: test")
        assertTrue(fence.canRebuild(stale, 0))
        assertTrue(fence.canRebuild(stale, 1))
        assertTrue(fence.canRebuild(stale, 2))
        assertTrue(!fence.canRebuild(stale, 3))
        assertTrue(!fence.canRebuild(IllegalStateException("PERMISSION_DENIED"), 0))
        fence.dispatched()
        assertTrue(!fence.canRebuild(stale, 0))
    }
}
