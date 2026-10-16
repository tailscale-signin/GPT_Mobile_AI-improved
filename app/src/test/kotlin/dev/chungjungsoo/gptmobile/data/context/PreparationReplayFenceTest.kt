package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationReplayFenceTest {
    @Test fun memoryChangesRebuildOnceBeforePhysicalWork() {
        val fence = PreparationReplayFence()
        val stale = IllegalArgumentException("MEMORY_STALE: revoked or edited fact")
        assertTrue(fence.canRebuild(stale, 0))
        assertFalse(fence.canRebuild(stale, 1))
        fence.dispatched()
        assertFalse(fence.canRebuild(stale, 0))
    }

    @Test fun policyChangesRebuildUpToThreeTimesButNeverAfterDispatch() {
        val fence = PreparationReplayFence()
        val stale = IllegalStateException("DISPATCH_POLICY_CHANGED: permissions changed")
        assertTrue(fence.canRebuild(stale, 0))
        assertTrue(fence.canRebuild(stale, 1))
        assertTrue(fence.canRebuild(stale, 2))
        assertFalse(fence.canRebuild(stale, 3))
        fence.dispatched()
        assertFalse(fence.canRebuild(stale, 0))
    }

    @Test fun arbitraryInputErrorsAndPermissionDenialsNeverReplay() {
        val fence = PreparationReplayFence()
        assertFalse(fence.canRebuild(IllegalArgumentException("invalid input"), 0))
        assertFalse(fence.canRebuild(IllegalStateException("PERMISSION_DENIED"), 0))
        assertFalse(fence.canRebuild(IllegalStateException("MEMORY_STALE: wrong error type"), 0))
    }
}
