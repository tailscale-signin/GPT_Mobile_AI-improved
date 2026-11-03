package dev.chungjungsoo.gptmobile.data.openrouter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRejectionCacheTest {
    @Test fun deterministicRejectionExpiresAndDoesNotCrossConfigurationIdentities() {
        var now = 0L
        val cache = PrivacyRejectionCache { now }
        cache.record("account-model-policy", "POLICY_RESTRICTED: unavailable")
        assertTrue(cache.blocked("account-model-policy"))
        assertFalse(cache.blocked("changed-account-model-policy"))
        assertFalse(cache.blocked("account-changed-model-policy"))
        assertFalse(cache.blocked("account-model-changed-policy"))
        now = 300_000
        assertFalse(cache.blocked("account-model-policy"))
    }

    @Test fun transientFailuresAreNeverCachedAsPolicyRejections() {
        val cache = PrivacyRejectionCache()
        cache.record("route", "HTTP 503 upstream unavailable")
        assertFalse(cache.blocked("route"))
        repeat(257) { cache.record("$it", "No endpoints found matching your data policy") }
        assertFalse(cache.blocked("0"))
        assertTrue(cache.blocked("256"))
    }
}
