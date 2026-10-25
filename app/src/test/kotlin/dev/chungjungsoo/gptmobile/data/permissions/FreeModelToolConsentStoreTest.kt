package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import android.content.Context
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FreeModelToolConsentStoreTest {
    private fun profile() = PlatformV2(uid = "profile", name = "Writer", compatibleType = ClientType.FREE, apiUrl = FreeAiProvider.OVHCLOUD.apiUrl, model = "model")

    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

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

    @Test fun actualHealthAndMemoryNamesRequireTypedCategoryAndCurrentScopedAuthority() {
        val store = FreeModelToolConsentStore(RuntimeEnvironment.getApplication())
        val profile = profile()
        store.grant(profile.uid, "memory")
        assertFalse(store.isGranted(profile, "memory", 7))
        store.grant(profile, "memory", chatId = 7)
        assertEquals(setOf(PersonalDataCategory.MEMORY), store.grantFor(profile, "memory", 7)?.categories)
        assertFalse(store.isGranted(profile, "memory", 8))
        assertFalse(store.isGranted(profile.copy(model = "different"), "memory", 7))
        assertFalse(store.isGranted(profile.copy(token = "different-account"), "memory", 7))
        assertTrue(runCatching { store.grant(profile, "health_connect_sleep", chatId = 7) }.isFailure)
        store.grant(profile, "health_connect_sleep", chatId = 7, healthRangeDays = 7)
        assertEquals(setOf(PersonalDataCategory.HEALTH), store.grantFor(profile, "health_connect_sleep", 7)?.categories)
        assertEquals(7, store.grantFor(profile, "health_connect_sleep", 7)?.healthRangeDays)
        assertEquals(PersonalDataCategory.HEALTH, personalDataCategory("selected__health_connect_heart_rate"))
        assertEquals(PersonalDataCategory.MEMORY, personalDataCategory("memory"))
        assertTrue(runCatching { store.grant(profile, "memory", chatId = 7, categories = setOf(PersonalDataCategory.HEALTH), healthRangeDays = 7) }.isFailure)
        assertTrue(runCatching { store.grant(profile, "memory", chatId = 7, expiresAt = System.currentTimeMillis() + 2 * 24 * 60 * 60_000L) }.isFailure)
    }

    @Test fun coarseLocationIsAUsableNarrowGrantAndExpiryAdvancesDispatchRevision() {
        val context = RuntimeEnvironment.getApplication()
        val store = FreeModelToolConsentStore(context)
        val profile = profile()
        store.grant(profile, "device_location", chatId = 7, categories = setOf(PersonalDataCategory.COARSE_LOCATION))
        val grant = requireNotNull(store.grantFor(profile, "device_location", 7))
        assertEquals(setOf(PersonalDataCategory.COARSE_LOCATION), grant.categories)
        assertTrue(grant.downstreamRecipients.isEmpty())
        assertNull(store.grantFor(profile.copy(enabled = false), "device_location", 7))
        val key = store.grants().single().first
        val revision = store.revision()
        val expired = grant.copy(issuedAt = System.currentTimeMillis() - 2000, expiresAt = System.currentTimeMillis() - 1000)
        context.getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE).edit().putString(key, Json.encodeToString(PersonalDataGrant.serializer(), expired)).commit()
        assertFalse(store.isGranted(profile, "device_location", 7))
        assertEquals(revision + 1, store.revision())
        assertTrue(store.grants().isEmpty())
    }
}
