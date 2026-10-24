package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FreeModelToolConsentStoreTest {
    @Test fun consentIsProfileScopedAndConnectionRevocationCoversItsGrants() {
        val context = RuntimeEnvironment.getApplication()
        val store = FreeModelToolConsentStore(context)
        store.grant("first-model", "test-connection:location")
        assertTrue(FreeModelToolConsentStore(context).isGranted("first-model", "test-connection:location"))
        assertFalse(store.isGranted("other-model", "test-connection:location"))
        assertFalse(store.isGranted("other-model", "test-connection:another-tool"))
        store.revokeConnection("test-connection")
        assertFalse(store.isGranted("first-model", "test-connection:location"))
        assertFalse(store.isGranted("other-model", "test-connection:location"))
    }

    @Test fun auditingAndRevokingOneGrantPreservesOtherAcknowledgements() {
        val context = RuntimeEnvironment.getApplication()
        val store = FreeModelToolConsentStore(context)
        store.grant("first-model", "one:search")
        store.grant("second-model", "two:search")
        val target = store.grants().single { it.second.contains("one:search") }
        assertTrue(target.second.contains("first-model"))
        store.revokeGrant(target.first)
        assertFalse(store.isGranted("first-model", "one:search"))
        assertTrue(store.isGranted("second-model", "two:search"))
        assertEquals(1, store.grants().size)
    }
}
