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
        assertEquals(0, ReplayAllowance.tokens(Int.MAX_VALUE, 5000, 4096, 4000))
        val large = ReplayAllowance.allocate(131072, 40000, 4096, 4096)
        assertEquals(131072, large.ceiling)
        assertEquals(4096, large.output)
        assertTrue(large.replay > 0)
        val ordinary = ReplayAllowance.allocate(32768, 12000, 2048, 4096)
        assertTrue(12000L + ordinary.replay + ordinary.output + 256 < ordinary.ceiling)
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

    @Test fun oneAdmissionRecordCarriesInputReplyEvidenceAndSafetyAcrossRoutes() {
        for ((context, prompt) in listOf(5632 to 2400, 8192 to 4000, 131072 to 40000)) {
            val allocation = ReplayAllowance.allocate(context, prompt, 4096, 4096)
            assertEquals(prompt, allocation.input)
            assertEquals(context, allocation.ceiling)
            assertTrue(allocation.output > 0 && allocation.replay > 0)
            assertTrue(allocation.input.toLong() + allocation.output + allocation.replay + allocation.safety <= allocation.ceiling)
        }
        val overflow = ReplayAllowance.allocate(5632, 9043, 4096, 4096)
        assertEquals(0, overflow.output)
        assertEquals(0, overflow.replay)
        assertEquals(9043, overflow.input)
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
