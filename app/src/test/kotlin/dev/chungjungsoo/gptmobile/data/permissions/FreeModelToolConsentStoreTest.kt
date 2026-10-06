package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
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
    @Test fun consentSurvivesProfileChangesAndIsRevokedForAllModels() {
        val context = RuntimeEnvironment.getApplication()
        val store = FreeModelToolConsentStore(context)
        store.grant("first-model", "test-connection:location")
        assertTrue(FreeModelToolConsentStore(context).isGranted("other-model", "test-connection:location"))
        assertFalse(store.isGranted("other-model", "test-connection:another-tool"))
        store.revokeConnection("test-connection")
        assertFalse(store.isGranted("first-model", "test-connection:location"))
        assertFalse(store.isGranted("other-model", "test-connection:location"))
    }
}
