package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreeProfileCredentialSaveTest {
    @Test fun addProfileFormPassesTheLlm7TokenToSecureStorageForNewAndExistingConnections() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = mockk<SettingRepository>(relaxed = true)
            every { repository.observePlatformV2s() } returns flowOf(emptyList())
            every { repository.observeProviderConnections() } returns flowOf(emptyList())
            every { repository.observeLocalRuntimeBackend() } returns flowOf(LocalRuntimeBackend.DEFAULT)
            every { repository.observeDebugMode() } returns flowOf(false)
            every { repository.observeFeatureSettings() } returns flowOf(dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
            coEvery { repository.fetchPlatformV2s() } returns emptyList()
            val connection = ProviderConnection(uid = FreeModelCatalog.CONNECTION_UID, name = "Free Models", compatibleType = ClientType.FREE, apiUrl = FreeAiProvider.KILO.apiUrl)
            coEvery { repository.addProviderConnection(any(), any()) } returns connection
            val model = SettingViewModelV2(repository, mockk(relaxed = true), mockk(relaxed = true))
            store.put("test", model)
            runCurrent()
            val profile = FreeAiProvider.LLM7.applyTo(PlatformV2(uid = "llm7", name = "LLM7", apiUrl = FreeAiProvider.LLM7.apiUrl, token = "synthetic-token"))
            model.addPlatform(profile, connection, "synthetic-token")
            runCurrent()
            model.addPlatform(profile.copy(providerConnectionUid = connection.uid), null, null)
            runCurrent()
            coVerify(exactly = 2) { repository.addPlatformV2(match { it.token == "synthetic-token" && it.providerConnectionUid == connection.uid && it.apiUrl == FreeAiProvider.LLM7.apiUrl }) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
