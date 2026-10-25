package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkResultPresentationTest {
    @Test fun blockedQueueItemWithoutARunOrScoreStillSupersedesAnOlderAttempt() {
        val profile = PlatformV2(uid = "p", name = "Profile", model = "model")
        val noHistory = profile.copy(uid = "q", name = "Untested")
        val old = BenchmarkRun("old", "p", "Profile", "OPENAI", "model", "old-config", false, BenchmarkMode.QUICK, 1000)
        val item = BenchmarkQueueItem(0, profile, "blocked", "new-config", BenchmarkQueueState.BLOCKED, failure = BenchmarkFailure("POLICY_BLOCKED", "admission", "Profile no longer authorized."))
        val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 2000, listOf(item))
        val results = benchmarkResultPresentations(listOf(profile, noHistory), listOf(old), listOf(batch), emptyList()).filter { it.mode == BenchmarkMode.QUICK }
        val blocked = results.single { it.profileUid == "p" }
        assertEquals("BLOCKED", blocked.status)
        assertEquals("admission", blocked.failure?.stage)
        assertNull(blocked.latestRun)
        assertTrue(blocked.attemptMetrics.isEmpty())
        assertEquals("blocked", blocked.latestQueueItem?.runId)
        val untested = results.single { it.profileUid == "q" }
        assertEquals("NOT_ATTEMPTED", untested.status)
        assertTrue(untested.attemptMetrics.isEmpty())
    }

    @Test fun aBlockedProfilePolicyRunDoesNotHideTheCapabilityAttemptOrAdoptLegacyPolicy() {
        val profile = PlatformV2(uid = "p", name = "Profile", model = "model")
        val legacy = BenchmarkRun("legacy", "p", "Profile", "OPENAI", "model", "old-config", false, BenchmarkMode.QUICK, 1000)
        val capability = legacy.copy(id = "capability", startedAt = 2000, executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1)
        val blocked = BenchmarkQueueItem(0, profile, "saved-settings-blocked", "profile-config", BenchmarkQueueState.BLOCKED, failure = BenchmarkFailure("POLICY_BLOCKED", "admission", "Profile no longer authorized."))
        val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 3000, listOf(blocked), executionPolicy = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1)
        val results = benchmarkResultPresentations(listOf(profile), listOf(legacy, capability), listOf(batch), emptyList()).filter { it.mode == BenchmarkMode.QUICK }
        assertEquals(3, results.size)
        assertEquals(3, results.map { it.identity }.distinct().size)
        assertEquals("legacy", results.single { it.executionPolicy == null }.latestRun?.id)
        assertEquals("capability", results.single { it.executionPolicy == BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 }.latestRun?.id)
        val savedSettings = results.single { it.executionPolicy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1 }
        assertEquals("BLOCKED", savedSettings.status)
        assertNull(savedSettings.latestRun)
        assertNull(savedSettings.retainedScore)
        assertNull(savedSettings.lastVerified)
    }
}
