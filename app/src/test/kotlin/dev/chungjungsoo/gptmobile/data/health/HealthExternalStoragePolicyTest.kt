package dev.chungjungsoo.gptmobile.data.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthExternalStoragePolicyTest {
    @Test fun writesRequireSeparateConsentButReadsDoNot() {
        assertTrue(HealthExternalStoragePolicy.requiresConsent("context_store"))
        assertTrue(HealthExternalStoragePolicy.requiresConsent("create_entities__memory"))
        assertFalse(HealthExternalStoragePolicy.requiresConsent("search_nodes"))
        assertFalse(HealthExternalStoragePolicy.requiresConsent("context_retrieve"))
    }
}
