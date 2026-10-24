package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore
import dev.chungjungsoo.gptmobile.data.permissions.ProviderActivationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProviderConsentBackupTest {
    @Test fun settingsBackupsExcludeConsentAndRestoreCannotReintroduceIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = PlatformV2(uid = "p", name = "OVH", apiUrl = FreeAiProvider.OVHCLOUD.apiUrl, model = FreeAiProvider.OVHCLOUD.model)
        val activation = ProviderActivationStore(context)
        val grants = FreeModelToolConsentStore(context)
        activation.accept(platform)
        grants.grant(platform, "device_location")
        val settings = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
        }
        val backup = CompleteBackupPreferences(context, settings)
        assertFalse("free_model_tool_consent_v2" in backup.readShared())
        val original = context.getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE).all.filterValues { it is Boolean }.mapValues { BackupValue("boolean", it.value.toString()) }
        backup.replace(emptyMap(), mapOf("free_model_tool_consent_v2" to original))
        assertTrue(activation.required(platform))
        assertFalse(grants.isGranted(platform, "device_location"))
    }
}
