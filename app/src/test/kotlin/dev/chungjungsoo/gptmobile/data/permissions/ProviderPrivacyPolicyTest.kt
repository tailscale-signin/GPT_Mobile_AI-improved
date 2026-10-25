package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import android.content.Context
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProviderPrivacyPolicyTest {
    private fun profile(provider: FreeAiProvider) = PlatformV2(uid = "recipient", name = provider.displayName, apiUrl = provider.apiUrl, model = provider.model)

    @Test fun unknownRoutesRequireActivationAndReceiptsNeverFollowACloneOrChangedModel() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = ProviderActivationStore(context)
        val profile = profile(FreeAiProvider.OVHCLOUD)
        assertTrue(store.required(profile))
        store.accept(profile)
        assertFalse(ProviderActivationStore(context).required(profile))
        assertTrue(store.required(profile.copy(uid = "clone")))
        assertTrue(store.required(profile.copy(model = "different-model")))
        assertTrue(store.required(profile.copy(token = "different-credential")))
        assertTrue(store.required(profile.copy(apiUrl = "${profile.apiUrl}?route=changed")))
        store.revoke(profile.uid)
        assertTrue(store.required(profile))
    }

    @Test fun personalToolGrantNamesOneRecipientAndNeverUnlocksProhibitedKiloRoute() {
        val context = RuntimeEnvironment.getApplication()
        val store = FreeModelToolConsentStore(context)
        val ovh = profile(FreeAiProvider.OVHCLOUD)
        store.grant(ovh, "device_location")
        assertTrue(store.isGranted(ovh, "device_location"))
        assertFalse(store.isGranted(ovh.copy(model = "new"), "device_location"))
        assertFalse(store.isGranted(ovh.copy(uid = "clone"), "device_location"))
        assertFalse(store.isGranted(ovh.copy(apiUrl = "${ovh.apiUrl}?route=changed"), "device_location"))
        assertFalse(store.isGranted(ovh, "health"))
        val kilo = profile(FreeAiProvider.KILO)
        assertThrows(IllegalStateException::class.java) { store.grant(kilo, "device_location") }
        assertFalse(store.isGranted(kilo, "device_location"))
        store.revokeProfile(ovh.uid)
        assertFalse(store.isGranted(ovh, "device_location"))
    }

    @Test fun legacyGlobalGrantsAreNotAuthorityAndRevokingOneUidDoesNotRevokeItsPrefixSibling() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("free_model_tool_consent_v1", Context.MODE_PRIVATE).edit().putBoolean("all-models:device_location", true).commit()
        val store = FreeModelToolConsentStore(context)
        assertFalse(store.isGranted(profile(FreeAiProvider.OVHCLOUD), "device_location"))
        store.grant("a", "connection:read")
        store.grant("ab", "connection:read")
        store.revoke("a", "connection:read")
        assertFalse(store.isGranted("a", "connection:read"))
        assertTrue(store.isGranted("ab", "connection:read"))
    }

    @Test fun verifiedNoRetentionDefaultIsIndependentOfAccountAuthentication() {
        val policy = ProviderPrivacyPolicy.forProvider(FreeAiProvider.LLM7)
        assertFalse(policy.enabledByDefault)
        assertTrue(policy.copy(retention = RetentionPolicy.VERIFIED_NO_RETENTION).enabledByDefault)
        assertFalse(policy.copy(provider = FreeAiProvider.OPENCODE, retention = RetentionPolicy.VERIFIED_NO_RETENTION).enabledByDefault)
    }
}
