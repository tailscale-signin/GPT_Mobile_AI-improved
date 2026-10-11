package dev.chungjungsoo.gptmobile.data.marketplace

import android.app.Application
import dev.chungjungsoo.gptmobile.data.agent.tool.NativeMarketplaceTool
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalDao
import dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeMarketplaceTest {

    @Test
    fun corruptRegistryRequiresExplicitDisabledRepair() = runBlocking {
        val file = files.newFile("damaged-registry.json").apply { writeText("{broken") }
        val registry = NativeMarketplaceRegistry(file, mockk(relaxed = true))
        var rejected = false
        try {
            registry.load()
        } catch (_: MarketplaceRegistryRepairRequired) {
            rejected = true
        }
        assertTrue(rejected)
        val id = GitHubMarketplaceCatalog.packages.first { NativeMarketplaceCatalog.supports(it) }.id
        registry.repairFromVerifiedPackages(setOf(id, "unknown-package"))
        assertEquals(setOf(id), registry.load().keys)
        assertFalse(registry.load().getValue(id).enabled)
        assertEquals(null, registry.load().getValue(id).credentialRef)
        assertTrue(files.root.listFiles().orEmpty().any { it.name.startsWith("damaged-registry.json.corrupt-") })
    }

    @Test fun everyDownloadableNativeAdapterHasExecutableDefinitionsAndARequestBuilder() {
        val entries = GitHubMarketplaceCatalog.packages.filter { it.runtime == MarketplaceRuntime.NATIVE }
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertTrue(entry.id, NativeMarketplaceCatalog.supports(entry))
            val definitions = NativeMarketplaceCatalog.definitions(entry)
            assertTrue(entry.id, definitions.isNotEmpty())
            definitions.forEach { definition ->
                val operation = definition.name.substringAfterLast("__")
                val fields = NativeMarketplaceCatalog.operations.getValue(entry.provider).getValue(operation)
                val arguments = buildJsonObject {
                    fields.forEach { field ->
                        when {
                            field.endsWith("latitude") -> put(field, 43.0)
                            field.endsWith("longitude") -> put(field, -79.0)
                            field == "organization_id" -> put(field, "123")
                            field == "resource_id" -> put(field, "resource-id")
                            field == "place_id" -> put(field, "ChIJ_test-123")
                            else -> put(field, "Toronto")
                        }
                    }
                }
                NativeMarketplaceCatalog.validate(entry.provider, operation, arguments)
                val config = NativeProviderConfiguration(NativePluginInstallation(endpoint = "https://managed.example/api", endpoints = mapOf("geocode" to "https://managed.example/geocode", "restrooms" to "https://managed.example/overpass")), "test-key")
                val request = NativeMarketplaceRequests.build(entry, operation, arguments, config)
                assertEquals("https", request.url.scheme)
                assertTrue(request.url.host.isNotBlank())
                assertFalse(request.url.toString().contains("builtin://"))
            }
        }
    }

    @Test fun googlePlacesNearbyAndDetailsUseCorrectHeadersCoordinatesAndSafeIds() = runBlocking {
        val entry = entry("google-places")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "private-test-key", 3, 50)
        registry.setEnabled(entry, true)
        val config = registry.configuration(entry)
        val nearby = NativeMarketplaceRequests.build(
            entry,
            "nearby",
            buildJsonObject {
                put("latitude", 43.0)
                put("longitude", -79.0)
            },
            config
        )
        assertEquals("POST", nearby.method)
        assertEquals("/v1/places:searchNearby", nearby.url.encodedPath)
        assertEquals("private-test-key", nearby.header("X-Goog-Api-Key"))
        assertEquals(null, nearby.header("Authorization"))
        val buffer = okio.Buffer()
        nearby.body!!.writeTo(buffer)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(43.0), body.getValue("locationRestriction").jsonObject.getValue("circle").jsonObject.getValue("center").jsonObject["latitude"])
        val details = NativeMarketplaceRequests.build(entry, "details", buildJsonObject { put("place_id", "ChIJ_test-123") }, config)
        assertEquals("GET", details.method)
        assertEquals("/v1/places/ChIJ_test-123", details.url.encodedPath)
        assertTrue(details.header("X-Goog-FieldMask").orEmpty().contains("displayName"))
        assertTrue(runCatching { NativeMarketplaceRequests.build(entry, "details", buildJsonObject { put("place_id", "../../secrets") }, config) }.isFailure)
    }

    @get:Rule val files = TemporaryFolder()
    private val vault = MemoryVault()
    private fun entry(provider: String) = (GitHubMarketplaceCatalog.packages + GitHubMarketplaceCatalog.legacyPackages).single { it.provider == provider }
    private fun registry(file: File = File(files.root, "plugins.json")) = NativeMarketplaceRegistry(file, vault)

    @Test fun legacyOpenStreetMapInstallationsBecomeOneDurableServiceWithTheirChoicesPreserved() = runBlocking {
        val file = File(files.root, "plugins.json")
        file.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, NativePluginInstallation>>(),
                mapOf(
                    "optional-nominatim" to NativePluginInstallation(enabled = false, endpoint = "https://maps.example/search", maxResults = 5, dailyLimit = 25, usageDay = "2026-10-07", usageCount = 3),
                    "optional-overpass" to NativePluginInstallation(enabled = true, endpoint = "https://maps.example/interpreter", usageDay = "2026-10-07", usageCount = 4, nextRequestAt = 1234)
                )
            )
        )
        val record = registry(file).load().getValue("optional-openstreetmap")
        assertEquals(setOf("geocode", "restrooms"), record.endpoints.keys)
        assertEquals(setOf("geocode"), record.disabledOperations)
        assertTrue(record.enabled)
        assertEquals(5, record.maxResults)
        assertEquals(25, record.dailyLimit)
        assertEquals(7, record.usageCount)
        assertEquals(1234L, record.nextRequestAt)
        val reloaded = registry(file).load()
        assertEquals(setOf("optional-openstreetmap"), reloaded.keys)
        assertEquals(record, reloaded.getValue("optional-openstreetmap"))
        registry(file).uninstall(entry("openstreetmap"))
        assertTrue(registry(file).load().isEmpty())
    }

    @Test fun managedEndpointsUseSeparateVaultSecretsAndExplicitPortApproval() = runBlocking {
        val entry = entry("openstreetmap")
        val registry = registry()
        registry.install(entry)
        val configs = mapOf(
            "geocode" to NativeEndpointConfig("https://maps.example:8443/search", EndpointAuthType.BEARER, allowCustomHttpsPort = true),
            "restrooms" to NativeEndpointConfig("https://maps.example/interpreter", EndpointAuthType.API_KEY)
        )
        assertFalse(NativeMarketplaceCatalog.validEndpoint(configs.getValue("geocode").url))
        assertTrue(NativeMarketplaceCatalog.validEndpoint(configs.getValue("geocode").url, true))
        assertFalse(NativeMarketplaceCatalog.validEndpoint("https://nominatim.openstreetmap.org:8443/search", true))
        registry.configure(entry, "", "", 5, 25, endpointConfigs = configs, endpointSecrets = mapOf("geocode" to "geocode-secret", "restrooms" to "restroom-secret"))
        registry.setEnabled(entry, true)
        val config = registry.configuration(entry)
        val geocode = NativeMarketplaceRequests.build(entry, "geocode", buildJsonObject { put("query", "Toronto") }, config)
        val restrooms = NativeMarketplaceRequests.build(entry, "restrooms", buildJsonObject { put("latitude", 43.65); put("longitude", -79.38) }, config)
        assertEquals(8443, geocode.url.port)
        assertEquals("Bearer geocode-secret", geocode.header("Authorization"))
        assertEquals(null, geocode.header("X-API-Key"))
        assertEquals("restroom-secret", restrooms.header("X-API-Key"))
        assertEquals(null, restrooms.header("Authorization"))
        val saved = registry.backupState().decodeToString()
        assertFalse(saved.contains("geocode-secret"))
        assertFalse(saved.contains("restroom-secret"))
        assertTrue(runCatching {
            registry.configure(entry, "", "", 5, 25, endpointConfigs = configs + ("geocode" to configs.getValue("geocode").copy(url = "https://other.example/search")))
        }.isFailure)
        registry.uninstall(entry)
        assertTrue(vault.records.isEmpty())
    }

    @Test fun disabledCapabilityDoesNotBlockSavingValidManagedEndpoint() = runBlocking {
        val entry = entry("openstreetmap")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "", 5, 25,
            disabledOperations = setOf("restrooms"),
            endpointConfigs = mapOf("geocode" to NativeEndpointConfig("https://maps.example/search"), "restrooms" to NativeEndpointConfig("stale invalid endpoint")))
        registry.setEnabled(entry, true)
        assertTrue(registry.configuration(entry).installation.ready(entry))
        assertTrue(runCatching { registry.configure(entry, "", "", 5, 25, endpointConfigs = mapOf("geocode" to NativeEndpointConfig("https://maps.example/search"))) }.isFailure)
    }

    @Test fun openStreetMapBundleRoutesEachCapabilityToItsOwnEndpoint() = runBlocking {
        val entry = entry("openstreetmap")
        val registry = registry()
        registry.install(entry)
        assertTrue(runCatching { registry.setEnabled(entry, true) }.isFailure)
        registry.configure(entry, "", "", 5, 25, endpoints = mapOf("geocode" to "https://maps.example/search", "restrooms" to "https://maps.example/interpreter"))
        registry.setEnabled(entry, true)
        val config = registry.configuration(entry)
        val geocode = NativeMarketplaceRequests.build(entry, "geocode", buildJsonObject { put("query", "Toronto") }, config)
        val restrooms = NativeMarketplaceRequests.build(
            entry,
            "restrooms",
            buildJsonObject {
                put("latitude", 43.65)
                put("longitude", -79.38)
            },
            config
        )
        assertEquals("/search", geocode.url.encodedPath)
        assertEquals("Toronto", geocode.url.queryParameter("q"))
        assertEquals("/interpreter", restrooms.url.encodedPath)
        assertEquals("POST", restrooms.method)
        assertEquals(null, geocode.header("Authorization"))
        assertEquals(null, restrooms.header("Authorization"))
        assertEquals(2, NativeMarketplaceCatalog.definitions(entry).size)
        val off = config.copy(installation = config.installation.copy(disabledOperations = setOf("geocode")))
        assertTrue(runCatching { NativeMarketplaceRequests.build(entry, "geocode", buildJsonObject { put("query", "Toronto") }, off) }.isFailure)
    }

    @Test fun noKeyInstallPersistsAndExistingToolStopsAfterDisableAndUninstall() = runBlocking {
        val entry = entry("refuge")
        val registry = registry()
        registry.install(entry)
        assertFalse(registry.state.value.getValue(entry.id).enabled)
        assertTrue(registry.state.value.getValue(entry.id).ready(entry))
        registry.setEnabled(entry, true)
        assertTrue(registry().load().getValue(entry.id).enabled)
        var requests = 0
        val tool = NativeMarketplaceTool(entry, NativeMarketplaceCatalog.definitions(entry).single(), registry) {
            requests++
            assertEquals(null, it.header("Authorization"))
            JsonArray(emptyList())
        }
        val args = buildJsonObject {
            put("latitude", 43.65)
            put("longitude", -79.38)
        }
        assertFalse(tool.execute("enabled", args).isError)
        registry.setEnabled(entry, false)
        assertTrue(tool.execute("disabled", args).isError)
        registry.uninstall(entry)
        assertTrue(tool.execute("removed", args).isError)
        assertEquals(1, requests)
        assertTrue(registry().load().isEmpty())
        assertTrue(vault.records.isEmpty())
    }

    @Test fun nativeReadsPassChatApprovalOnlyWhileInstalledEnabledAndValid() = runBlocking {
        val entry = entry("refuge")
        val registry = registry()
        val database = mockk<ChatDatabaseV2>()
        val dao = mockk<ToolApprovalDao>()
        every { database.toolApprovalDao() } returns dao
        every { dao.pending() } returns flowOf(emptyList())
        val approvals = ToolApprovalManager(database, mockk<ToolConnectionRepository>(), nativeMarketplace = registry)
        val args = buildJsonObject {
            put("latitude", 43.65)
            put("longitude", -79.38)
        }
        assertFalse(approvals.authorize(entry.id, "run", "missing", "restrooms", args))
        registry.install(entry)
        assertFalse(approvals.authorize(entry.id, "run", "disabled", "restrooms", args))
        registry.setEnabled(entry, true)
        assertTrue(approvals.authorize(entry.id, "run", "enabled", "restrooms", args))
        assertFalse(approvals.authorize(entry.id, "run", "unknown", "write", args))
        assertFalse(approvals.authorize(entry.id, "run", "invalid", "restrooms", buildJsonObject {}))
        registry.uninstall(entry)
        assertFalse(approvals.authorize(entry.id, "run", "removed", "restrooms", args))
    }
}
