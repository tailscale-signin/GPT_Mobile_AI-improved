package dev.chungjungsoo.gptmobile.data.localruntime

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        assertEquals(1, qnnRuntime.loadEngineCalls.size)
        assertTrue(liteRtRuntime.loadEngineCalls.isEmpty())
    }

    @Test
    fun loadEngine_whenLiteRtSelected_delegatesToLiteRtRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.LITERT_LM
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")

        router.loadEngine(spec)

        assertEquals(1, liteRtRuntime.loadEngineCalls.size)
        assertTrue(qnnRuntime.loadEngineCalls.isEmpty())
    }

    @Test
    fun loadEngine_whenQnnFails_fallsBackToLiteRtRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.QUALCOMM_QNN
        qnnRuntime.failLoadEngineIf = { RuntimeException("QNN native load error") }
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")

        router.loadEngine(spec)

        assertEquals(1, qnnRuntime.loadEngineCalls.size)
        assertEquals(1, liteRtRuntime.loadEngineCalls.size)
        assertTrue(router.isEngineLoaded(spec))
    }

    @Test
    fun createConversationAndSendMessage_afterFallback_delegatesToLiteRtRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.QUALCOMM_QNN
        qnnRuntime.failLoadEngineIf = { RuntimeException("QNN load error") }
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")
        router.loadEngine(spec)
        router.createConversation(LocalConversationConfig())

        val events = router.sendMessage("Fallback test", emptyList()).toList()

        assertEquals(1, liteRtRuntime.createConversationCalls.size)
        assertEquals(listOf("Fallback test"), liteRtRuntime.sendMessageCalls)
        assertTrue(qnnRuntime.createConversationCalls.isEmpty())
        assertTrue(qnnRuntime.sendMessageCalls.isEmpty())
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun sendMessage_routesToActiveConversationRuntime() = runTest {
        fakeSettingRepository.backend = LocalRuntimeBackend.QUALCOMM_QNN
        val spec = LocalEngineSpec(modelPath = "/path/to/model.bin")
        router.loadEngine(spec)
        router.createConversation(LocalConversationConfig())

        val events = router.sendMessage("Hello NPU", emptyList()).toList()

        assertEquals(listOf("Hello NPU"), qnnRuntime.sendMessageCalls)
        assertTrue(liteRtRuntime.sendMessageCalls.isEmpty())
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun cancelActive_cancelsBothRuntimes() {
        router.cancelActive()

        assertEquals(1, qnnRuntime.cancelActiveCalls)
        assertEquals(1, liteRtRuntime.cancelActiveCalls)
    }

    @Test
    fun unloadEngine_unloadsBothRuntimes() = runTest {
        router.unloadEngine()

        assertEquals(1, qnnRuntime.unloadEngineCalls)
        assertEquals(1, liteRtRuntime.unloadEngineCalls)
    }

    @Test
    fun closeConversation_closesBothRuntimes() = runTest {
        router.closeConversation()

        assertEquals(1, qnnRuntime.closeConversationCalls)
        assertEquals(1, liteRtRuntime.closeConversationCalls)
    }

    @Test
    fun hasOpenConversation_reflectsAnyRuntimeWithOpenConversation() = runTest {
        assertFalse(router.hasOpenConversation())

        qnnRuntime.createConversation(LocalConversationConfig())
        assertTrue(router.hasOpenConversation())

        qnnRuntime.closeConversation()
        liteRtRuntime.createConversation(LocalConversationConfig())
        assertTrue(router.hasOpenConversation())
    }

    private class FakeRouterSettingRepository : SettingRepository {
        var backend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN

        override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = backend

        override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
            this.backend = backend
        }

        override suspend fun fetchPlatforms(): List<Platform> = emptyList()
        override suspend fun fetchPlatformV2s(): List<PlatformV2> = emptyList()
        override fun observePlatformV2s(): Flow<List<PlatformV2>> = emptyFlow()
        override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = emptyFlow()
        override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
        override suspend fun migrateToPlatformV2() = Unit
        override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()
        override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
        override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
        override suspend fun addPlatformV2(platform: PlatformV2) = Unit
        override suspend fun updatePlatformV2(platform: PlatformV2) = Unit
        override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
        override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null
        override suspend fun exportConfigurationJson(): String = "{}"
        override suspend fun importConfigurationJson(json: String): Result<Int> = Result.success(0)
    }
}
