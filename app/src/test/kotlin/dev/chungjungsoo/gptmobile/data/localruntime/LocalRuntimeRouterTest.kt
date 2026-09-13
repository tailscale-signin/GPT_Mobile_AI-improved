package dev.chungjungsoo.gptmobile.data.localruntime

import com.google.common.truth.Truth.assertThat
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SecretMigrationError
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class LocalRuntimeRouterTest {

    private lateinit var qnnRuntime: FakeLocalRuntime
    private lateinit var liteRtRuntime: FakeLocalRuntime
    private lateinit var fakeSettingRepository: FakeRouterSettingRepository
    private lateinit var router: LocalRuntimeRouter

    @Before
    fun setUp() {
        qnnRuntime = FakeLocalRuntime()
        liteRtRuntime = FakeLocalRuntime()
        fakeSettingRepository = FakeRouterSettingRepository()
        router = LocalRuntimeRouter(
            settingRepository = fakeSettingRepository,
            qnnRuntime = qnnRuntime,
            liteRtRuntime = liteRtRuntime
        )
    }

    @Test
    fun loadEngine_whenQnnSelected_delegatesToQnnRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.QUALCOMM_QNN
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")

        router.loadEngine(spec)

        assertThat(qnnRuntime.loadEngineCalls).hasSize(1)
        assertThat(liteRtRuntime.loadEngineCalls).isEmpty()
    }

    @Test
    fun loadEngine_whenLiteRtSelected_delegatesToLiteRtRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.LITERT_LM
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")

        router.loadEngine(spec)

        assertThat(liteRtRuntime.loadEngineCalls).hasSize(1)
        assertThat(qnnRuntime.loadEngineCalls).isEmpty()
    }

    @Test
    fun sendMessage_routesToActiveConversationRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.QUALCOMM_QNN
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")
        router.loadEngine(spec)
        router.createConversation(LocalConversationConfig())

        val events = router.sendMessage("Hello NPU", emptyList()).toList()

        assertThat(qnnRuntime.sendMessageCalls).containsExactly("Hello NPU")
        assertThat(liteRtRuntime.sendMessageCalls).isEmpty()
        assertThat(events).isNotEmpty()
    }

    @Test
    fun cancelActive_cancelsBothRuntimes() {
        router.cancelActive()

        assertThat(qnnRuntime.cancelActiveCalls).isEqualTo(1)
        assertThat(liteRtRuntime.cancelActiveCalls).isEqualTo(1)
    }

    @Test
    fun unloadEngine_unloadsBothRuntimes() = runTest {
        router.unloadEngine()

        assertThat(qnnRuntime.unloadEngineCalls).isEqualTo(1)
        assertThat(liteRtRuntime.unloadEngineCalls).isEqualTo(1)
    }

    @Test
    fun closeConversation_closesBothRuntimes() = runTest {
        router.closeConversation()

        assertThat(qnnRuntime.closeConversationCalls).isEqualTo(1)
        assertThat(liteRtRuntime.closeConversationCalls).isEqualTo(1)
    }

    @Test
    fun hasOpenConversation_reflectsAnyRuntimeWithOpenConversation() = runTest {
        assertThat(router.hasOpenConversation()).isFalse()

        qnnRuntime.createConversation(LocalConversationConfig())
        assertThat(router.hasOpenConversation()).isTrue()

        qnnRuntime.closeConversation()
        liteRtRuntime.createConversation(LocalConversationConfig())
        assertThat(router.hasOpenConversation()).isTrue()
    }

    private class FakeRouterSettingRepository : SettingRepository {
        var backend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN

        override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = backend

        override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
            this.backend = backend
        }

        override suspend fun fetchPlatforms(): List<Platform> = emptyList()
        override fun observePlatformV2s(): Flow<List<PlatformV2>> = emptyFlow()
        override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = emptyFlow()
        override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
        override suspend fun migrateToPlatformV2() = Unit
        override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()
        override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    }
}
