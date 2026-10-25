package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufNegativeCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun isolatedVerifyAllowsExactlyOneTargetAndRetainsUnrelatedProtection() {
        var now = 1000L
        val cache = GgufNegativeCache(temporary.newFolder()) { now }
        val target = "a".repeat(64)
        val other = "b".repeat(64)
        cache.remember(target, GgufNegativeKind.DETERMINISTIC_UNSUPPORTED, "unsupported tensor")
        cache.remember(other, GgufNegativeKind.NATIVE_CRASH, "prior worker aborted")
        cache.allowOneVerification(target)
        assertNull(cache.beforeAttempt(target, 1000))
        assertNotNull(cache.beforeAttempt(target, 1000))
        assertNotNull(cache.beforeAttempt(other, 1000))
        cache.remember(target, GgufNegativeKind.DETERMINISTIC_UNSUPPORTED, "fresh probe still unsupported")
        now += 10 * 60_000
        assertNotNull(cache.beforeAttempt(target, 1000))
        cache.success(target)
        assertNull(cache.beforeAttempt(target, 1000))
        assertNotNull(cache.beforeAttempt(other, 1000))
    }

    @Test fun transientAndResourceFailuresHaveDifferentRecoveryConditions() {
        var now = 0L
        val cache = GgufNegativeCache(temporary.newFolder()) { now }
        val transient = "c".repeat(64)
        val memory = "d".repeat(64)
        cache.remember(transient, GgufNegativeKind.TRANSIENT_INITIALIZATION, "driver init failed")
        cache.remember(memory, GgufNegativeKind.RESOURCE_PRESSURE, "allocation failed", 1000)
        assertNotNull(cache.beforeAttempt(memory, 1001))
        assertNull(cache.beforeAttempt(memory, 1000 + 64L * 1024 * 1024))
        now = 30_000
        assertNull(cache.beforeAttempt(memory, 1000))
        assertNotNull(cache.beforeAttempt(transient, 1000))
        now = 300_000
        assertNull(cache.beforeAttempt(transient, 1000))
    }
}
