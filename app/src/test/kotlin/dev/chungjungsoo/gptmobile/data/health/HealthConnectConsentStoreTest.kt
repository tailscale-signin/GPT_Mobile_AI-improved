package dev.chungjungsoo.gptmobile.data.health

import android.app.Application
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HealthConnectConsentStoreTest {
    @Test
    fun sharingDoesNotImplicitlyAllowExternalStorage() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = HealthConnectConsentStore(context)
        assertFalse(store.isAllowed())
        assertFalse(store.isExternalStorageAllowed())
        store.setAllowed(true)
        assertTrue(store.isAllowed())
        assertFalse(store.isExternalStorageAllowed())
        assertFalse(HealthConnectConsentStore(context).isExternalStorageAllowed())
    }

    @Test
    fun explicitExternalConsentSurvivesRestartAndCanBeRevokedIndependently() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = HealthConnectConsentStore(context)
        store.setAllowed(true)
        store.setExternalStorageAllowed(true)
        val restarted = HealthConnectConsentStore(context)
        assertTrue(restarted.isExternalStorageAllowed())
        restarted.setExternalStorageAllowed(false)
        assertTrue(restarted.isAllowed())
        assertFalse(restarted.isExternalStorageAllowed())
        assertFalse(HealthConnectConsentStore(context).isExternalStorageAllowed())
    }

    @Test
    fun revokingAiSharingClearsExternalConsentAcrossRestartAndReenable() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = HealthConnectConsentStore(context)
        store.setAllowed(true)
        store.setExternalStorageAllowed(true)
        store.setAllowed(false)
        assertFalse(store.allowed.value)
        assertFalse(store.externalStorageAllowed.value)
        val restarted = HealthConnectConsentStore(context)
        assertFalse(restarted.isAllowed())
        assertFalse(restarted.isExternalStorageAllowed())
        restarted.setAllowed(true)
        assertFalse(restarted.isExternalStorageAllowed())
    }

    @Test
    fun externalConsentCannotBeGrantedWithoutAiConsent() = runTest {
        val store = HealthConnectConsentStore(RuntimeEnvironment.getApplication())
        var rejected = false
        try {
            store.setExternalStorageAllowed(true)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(store.isExternalStorageAllowed())
    }

    @Test
    fun malformedConsentFailsClosedAndUsesNoBackupStorage() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val directory = context.noBackupFilesDir
        directory.mkdirs()
        File(directory, "health-connect-ai-consent-v1").writeText("corrupted")
        File(directory, "health-connect-external-storage-consent-v1").writeText("explicit-external-memory-consent-v1")
        val store = HealthConnectConsentStore(context)
        assertFalse(store.isAllowed())
        assertFalse(store.isExternalStorageAllowed())
        assertFalse(File(context.filesDir, "health-connect-ai-consent-v1").exists())
        assertFalse(File(context.filesDir, "health-connect-external-storage-consent-v1").exists())
    }
}
