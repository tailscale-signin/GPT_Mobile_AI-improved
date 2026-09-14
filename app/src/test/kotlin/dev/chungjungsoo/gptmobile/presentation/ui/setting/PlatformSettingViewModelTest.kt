package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.SavedStateHandle
import dev.chungjungsoo.gptmobile.data.catalog.CatalogDefaultConfig
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.catalog.SocVariant
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBindingWithConnection
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.localmodel.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.FakeLocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SecretMigrationError
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.domain.model.AcceleratorUnavailableReason
import dev.chungjungsoo.gptmobile.domain.model.MAX_HIGH_RAM_CONTEXT_TOKENS
import dev.chungjungsoo.gptmobile.domain.tools.AgentToolResolver
import dev.chungjungsoo.gptmobile.domain.tools.McpClientManager
import dev.chungjungsoo.gptmobile.domain.tools.McpOAuthClient
import dev.chungjungsoo.gptmobile.domain.tools.McpOAuthCoordinator
import dev.chungjungsoo.gptmobile.domain.tools.NetworkClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlatformSettingViewModelTest {

    @Test
    fun `initializes non-local platform with default settings`() = runTest {
        val platform = PlatformV2(
            uid = "remote-1",
            name = "OpenAI",
            compatibleType = ClientType.OPENAI,
            enabled = true,
            apiUrl = "https://api.openai.com/v1/",
            model = "gpt-4o"
        )
        val settings = FakeSettingRepository(platform)
        val viewModel = testViewModel(settings = settings, platformUid = platform.uid)

        assertFalse(viewModel.isLocalPlatform.value)
    }

    @Test
    fun `initializes local platform and surfaces local controls`() = runTest {
        val platform = localPlatform()
        val settings = FakeSettingRepository(platform)
        val viewModel = testViewModel(settings = settings, platformUid = platform.uid)

        assertEquals(true, viewModel.isLocalPlatform.value)
        assertEquals("gemma3-1b-it", viewModel.selectedModel.value)
        assertEquals(1.0f, viewModel.temperature.value)
        assertEquals(0.95f, viewModel.topP.value)
        assertEquals(64, viewModel.topK.value)
        assertEquals(1024, viewModel.maxTokens.value)
        assertEquals(LocalAccelerators.GPU, viewModel.selectedAccelerator.value)
    }

    @Test
    fun `updating temperature persists to the local profile`() = runTest {
        val settings = FakeSettingRepository(localPlatform())
        val viewModel = testViewModel(settings = settings)

        viewModel.setTemperature(0.7f)

        assertEquals(0.7f, settings.updatedPlatforms.single().temperature)
    }

    @Test
    fun `updating top-p persists to the local profile`() = runTest {
        val settings = FakeSettingRepository(localPlatform())
        val viewModel = testViewModel(settings = settings)

        viewModel.setTopP(0.85f)

        assertEquals(0.85f, settings.updatedPlatforms.single().topP)
    }

    @Test
    fun `updating top-k persists to the local profile`() = runTest {
        val settings = FakeSettingRepository(localPlatform())
        val viewModel = testViewModel(settings = settings)

        viewModel.setTopK(72)

        assertEquals(72, settings.updatedPlatforms.single().topK)
    }

    @Test
    fun `updating max-tokens persists to the local profile`() = runTest {
        val settings = FakeSettingRepository(localPlatform())
        val viewModel = testViewModel(settings = settings)

        viewModel.setMaxTokens(2048)

        assertEquals(2048, settings.updatedPlatforms.single().maxTokens)
    }

    @Test
    fun `device with 12GB or more allows context up to 4096 tokens`() = runTest {
        val settings = FakeSettingRepository(localPlatform(maxTokens = 2048))
        val viewModel = testViewModel(settings = settings, deviceRamGb = 12)

        viewModel.setMaxTokens(4096)

        assertEquals(MAX_HIGH_RAM_CONTEXT_TOKENS, viewModel.maxAllowedContextTokens)
        assertEquals(4096, settings.updatedPlatforms.single().maxTokens)
    }

    @Test
    fun `accelerator selection falls back to CPU when unsupported by model`() = runTest {
        val settings = FakeSettingRepository(localPlatform(accelerator = LocalAccelerators.GPU))
        val entry = catalogEntry("gemma3-1b-it", listOf(LocalAccelerators.CPU))
        val viewModel = testViewModel(
            settings = settings,
            catalog = FakeModelCatalogRepository(listOf(entry))
        )

        assertEquals(LocalAccelerators.CPU, viewModel.selectedAccelerator.value)
    }

    @Test
    fun `unsupported NPU surface reason why it cannot be enabled`() = runTest {
        val settings = FakeSettingRepository(localPlatform(accelerator = LocalAccelerators.CPU))
        val entry = catalogEntry("gemma3-1b-it", listOf(LocalAccelerators.CPU, LocalAccelerators.GPU))
        val viewModel = testViewModel(
            settings = settings,
            catalog = FakeModelCatalogRepository(listOf(entry)),
            deviceSocModel = "Tensor G4"
        )

        val item = viewModel.acceleratorItems.value.first { it.accelerator == LocalAccelerators.NPU }
        assertFalse(item.isSupported)
        assertEquals(AcceleratorUnavailableReason.MODEL_INCOMPATIBLE, item.unavailableReason)
    }

    private fun testViewModel(
        dao: ToolConnectionDao = FakeToolConnectionDao(),
        vault: SecretVault = FakeSecretVault(),
        settings: SettingRepository = FakeSettingRepository(),
        localModelRepository: LocalModelRepository = FakeLocalModelRepository(),
        catalog: ModelCatalogRepository = FakeModelCatalogRepository(),
        deviceSocModel: String = "Tensor G4",
        deviceRamGb: Int = 8,
        platformUid: String = "profile-1"
    ): PlatformSettingViewModel {
        val networkClient = NetworkClient(CIO.create())
        val connectionRepository = ToolConnectionRepository(dao, vault)
        val oauthClient = McpOAuthClient(networkClient)
        val oauthCoordinator = McpOAuthCoordinator(connectionRepository, oauthClient)
        val mcpManager = McpClientManager(connectionRepository, networkClient, oauthCoordinator)
        val toolResolver = AgentToolResolver(connectionRepository, mcpManager)

        return PlatformSettingViewModel(
            settingRepository = settings,
            modelCatalogRepository = catalog,
            toolConnectionRepository = connectionRepository,
            agentToolResolver = toolResolver,
            localModelRepository = localModelRepository,
            deviceSocModel = deviceSocModel,
            deviceRamGb = deviceRamGb,
            savedStateHandle = SavedStateHandle(mapOf("platformUid" to platformUid))
        )
    }

    private fun testConnection(
        connectionUid: String,
        type: String = ToolConnectionType.FIRECRAWL
    ): ToolConnection = ToolConnection(
        connectionUid = connectionUid,
        name = connectionUid,
        alias = connectionUid.replace("-", "_"),
        type = type,
        endpointUrl = "https://example.com",
        authType = ToolConnectionAuthType.BEARER,
        secretRef = null,
        oauthClientId = null
    )

    private fun testBinding(profileUid: String, connectionUid: String): AgentToolBinding = AgentToolBinding(
        bindingUid = "$profileUid:$connectionUid:web_search",
        profileUid = profileUid,
        connectionUid = connectionUid,
        toolName = "web_search"
    )
}

internal class FakeToolConnectionDao(
    val connections: MutableMap<String, ToolConnection> = mutableMapOf(),
    val bindings: MutableList<AgentToolBinding> = mutableListOf()
) : ToolConnectionDao {
    override suspend fun listConnections(): List<ToolConnection> = connections.values.toList()

    override suspend fun getConnection(connectionUid: String): ToolConnection? = connections[connectionUid]

    override suspend fun getConnectionsByUids(connectionUids: List<String>): List<ToolConnection> = connectionUids.mapNotNull(connections::get)

    override suspend fun upsertConnection(connection: ToolConnection) {
        connections[connection.connectionUid] = connection
    }

    override suspend fun deleteConnectionByUid(connectionUid: String) {
        connections.remove(connectionUid)
        bindings.removeAll { it.connectionUid == connectionUid }
    }

    override suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding> = bindings.filter { it.profileUid == profileUid }

    override suspend fun insertBinding(binding: AgentToolBinding) {
        bindings.removeAll { it.bindingUid == binding.bindingUid }
        bindings += binding
    }

    override suspend fun deleteConnectionToolBindingsForTypes(
        profileUid: String,
        toolName: String,
        connectionTypes: List<String>
    ) {
        bindings.removeAll { binding ->
            binding.profileUid == profileUid &&
                binding.toolName == toolName &&
                binding.connectionUid?.let { connections[it]?.type in connectionTypes } == true
        }
    }

    override suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String) {
        bindings.removeAll { it.profileUid == profileUid && it.toolName == toolName && it.connectionUid == null }
    }

    override suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String) {
        bindings.removeAll { binding ->
            binding.profileUid == profileUid &&
                binding.connectionUid?.let { connections[it]?.type == connectionType } == true
        }
    }

    override suspend fun listBindingsWithConnections(profileUid: String): List<AgentToolBindingWithConnection> = listBindingsByProfile(profileUid).map { binding ->
        AgentToolBindingWithConnection(binding, binding.connectionUid?.let(connections::get))
    }
}

internal class FakeSecretVault : SecretVault {
    val values = mutableMapOf<String, ByteArray>()

    override suspend fun put(secretRef: String, secret: ByteArray) {
        values[secretRef] = secret.copyOf()
    }

    override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()

    override suspend fun delete(secretRef: String) {
        values.remove(secretRef)?.fill(0)
    }
}

private class FakeSettingRepository(
    initialPlatform: PlatformV2 = PlatformV2(
        uid = "profile-1",
        name = "OpenAI",
        compatibleType = ClientType.OPENAI,
        enabled = true,
        apiUrl = "https://example.com",
        model = "gpt"
    )
) : SettingRepository {
    private var platform = initialPlatform
    val updatedPlatforms = mutableListOf<PlatformV2>()
    var localRuntimeBackend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN
    var debugMode: Boolean = false

    override suspend fun fetchPlatforms(): List<Platform> = emptyList()

    override suspend fun fetchPlatformV2s(): List<PlatformV2> = listOf(platform)

    override fun observePlatformV2s(): Flow<List<PlatformV2>> = flowOf(listOf(platform))

    override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = flowOf(if (platform.uid == uid) platform else null)

    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
    override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = localRuntimeBackend
    override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
        localRuntimeBackend = backend
    }
    override suspend fun getDebugMode(): Boolean = debugMode
    override suspend fun updateDebugMode(enabled: Boolean) {
        debugMode = enabled
    }
    override fun observeDebugMode(): Flow<Boolean> = flowOf(debugMode)
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit

    override suspend fun getFavoriteGroups(): List<String> = emptyList()
    override suspend fun saveFavoriteGroups(groups: List<String>) = Unit
    override fun observeFavoriteGroups(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getFavoriteMessageGroups(): Map<Int, String> = emptyMap()
    override suspend fun saveFavoriteMessageGroups(messageGroups: Map<Int, String>) = Unit
    override fun observeFavoriteMessageGroups(): Flow<Map<Int, String>> = flowOf(emptyMap())

    override suspend fun addPlatformV2(platform: PlatformV2) = Unit
    override suspend fun updatePlatformV2(platform: PlatformV2) {
        this.platform = platform
        updatedPlatforms += platform
    }
    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null
    override suspend fun exportConfigurationJson(): String = "{}"
    override suspend fun importConfigurationJson(json: String): Result<Int> = Result.success(0)
}

private class FakeModelCatalogRepository(
    private val entries: List<CatalogEntry> = emptyList()
) : ModelCatalogRepository {
    override suspend fun getVisibleEntries(): List<CatalogEntry> = entries
}

private fun localPlatform(
    model: String = "gemma3-1b-it",
    temperature: Float? = 1.0f,
    topP: Float? = 0.95f,
    topK: Int? = 64,
    maxTokens: Int? = 1024,
    accelerator: String? = LocalAccelerators.GPU
) = PlatformV2(
    uid = "local-1",
    name = "Local",
    compatibleType = ClientType.LITERT_LM,
    enabled = true,
    apiUrl = "",
    model = model,
    temperature = temperature,
    topP = topP,
    topK = topK,
    maxTokens = maxTokens,
    accelerator = accelerator
)

private fun catalogEntry(
    id: String,
    supportedAccelerators: List<String>,
    defaults: CatalogDefaultConfig = CatalogDefaultConfig(),
    socToModelFiles: Map<String, SocVariant> = emptyMap()
) = CatalogEntry(
    id = id,
    displayName = id,
    supportedAccelerators = supportedAccelerators,
    defaultConfig = defaults,
    socToModelFiles = socToModelFiles
)
