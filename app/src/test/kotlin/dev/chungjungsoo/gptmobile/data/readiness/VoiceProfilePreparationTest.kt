package dev.chungjungsoo.gptmobile.data.readiness

import android.content.Context
import android.net.ConnectivityManager
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPreparation
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VoiceProfilePreparationTest {
    @Test fun onlyTheSelectedPublicTransportWarmsAndPrivateOrMeteredRoomsStayOffIt() = runTest {
        val runtime = mockk<LocalRuntime>()
        every { runtime.getHardwareState() } returns DeviceHardwareState()
        val network = mockk<ConnectivityManager>()
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns network
        every { network.isActiveNetworkMetered } returns false
        val transport = mockk<ProviderTransportWarmer>(relaxed = true)
        val local = mockk<LocalAssistPreparation>(relaxed = true)
        val owner = ChatReadinessCoordinator(context, mockk(), mockk(), mockk(), mockk(), local, runtime, mockk(), mockk(), transport, this)
        val profile = PlatformV2(name = "Selected", model = "chosen", apiUrl = "https://selected.example/v1")
        val settings = ReadinessSettings()
        val public = ConversationPrivacyContext(8, false)
        val private = ConversationPrivacyContext(9, true)
        owner.prepareVoiceProfile(profile, settings, public)
        owner.prepareVoiceProfile(profile, settings, private)
        owner.prepareVoiceProfile(profile, settings.copy(enabled = false), public)
        owner.prepareVoiceProfile(profile, settings, ConversationPrivacyContext(null, false))
        owner.prepareVoiceProfile(profile.copy(enabled = false), settings, public)
        every { network.isActiveNetworkMetered } returns true
        owner.prepareVoiceProfile(profile, settings, public)
        coVerify(exactly = 1) { transport.warm(profile.apiUrl, any(), any()) }
        owner.prepareVoiceProfile(profile, settings.copy(warmMeteredNetworks = true), public)
        coVerify(exactly = 2) { transport.warm(profile.apiUrl, any(), any()) }
        val selectedLocal = profile.copy(compatibleType = ClientType.LITERT_LM)
        owner.prepareVoiceProfile(selectedLocal, settings, private)
        owner.prepareVoiceProfile(selectedLocal, settings.copy(prepareSelectedLocalModel = true), private)
        owner.prepareVoiceProfile(profile.copy(compatibleType = ClientType.LLAMA), settings.copy(warmMeteredNetworks = true), public)
        coVerify(exactly = 1) { local.preparePrimary(selectedLocal) }
        coVerify(exactly = 2) { transport.warm(any(), any(), any()) }
    }
}
