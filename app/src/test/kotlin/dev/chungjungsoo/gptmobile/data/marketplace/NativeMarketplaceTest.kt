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

    @Test fun echoedKeysAreRedactedAndProviderResultsKeepTheirConfiguredBound() = runBlocking {
        val entry = entry("ticketmaster")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "private-test-key", 3, 50)
        registry.setEnabled(entry, true)
        val tool = NativeMarketplaceTool(entry, NativeMarketplaceCatalog.definitions(entry).single(), registry) {
            buildJsonObject {
                put("_links", buildJsonObject { put("href", "https://provider.example/events?apikey=private-test-key") })
                put("_embedded", buildJsonObject { put("events", JsonArray((1..20).map { JsonPrimitive("Event $it") })) })
            }
        }
        val result = tool.execute(
            "call",
            buildJsonObject {
                put("query", "concert")
                put("location", "Toronto")
            }
        )
        assertFalse(result.isError)
        assertFalse(result.content.toString().contains("private-test-key"))
        assertTrue(result.content.toString().contains("[redacted]"))
        val json = (result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json).value.jsonObject
        assertEquals(3, (json.getValue("data").jsonObject.getValue("_embedded").jsonObject.getValue("events") as JsonArray).size)
    }

    @Test fun requiredKeyUsesVaultAndUninstallDeletesIt() = runBlocking {
        val entry = entry("yelp")
        val registry = registry()
        registry.install(entry)
        assertFalse(registry.state.value.getValue(entry.id).ready(entry))
        assertTrue(runCatching { registry.setEnabled(entry, true) }.isFailure)
        registry.configure(entry, "", "private-test-key", 10, 50)
        val ref = registry.state.value.getValue(entry.id).credentialRef!!
        assertFalse(File(files.root, "plugins.json").readText().contains("private-test-key"))
        registry.setEnabled(entry, true)
        val config = registry.configuration(entry)
        val request = NativeMarketplaceRequests.build(
            entry,
            "businesses",
            buildJsonObject {
                put("query", "coffee")
                put("location", "Toronto")
            },
            config
        )
        assertEquals("Bearer private-test-key", request.header("Authorization"))
        registry.uninstall(entry)
        assertFalse(vault.records.containsKey(ref))
        assertTrue(registry().load().isEmpty())
    }

    @Test fun failedCredentialDeletionKeepsDisabledRecordForRetry() = runBlocking {
        val entry = entry("yelp")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "private-test-key", 10, 50)
        registry.setEnabled(entry, true)
        vault.failDelete = true
        assertTrue(runCatching { registry.uninstall(entry) }.isFailure)
        assertFalse(registry.state.value.getValue(entry.id).enabled)
        assertTrue(vault.records.isNotEmpty())
        vault.failDelete = false
        registry.uninstall(entry)
        assertTrue(vault.records.isEmpty())
        assertTrue(registry.state.value.isEmpty())
    }

    @Test fun clearingKeyDisablesPluginAndMissingVaultKeyCannotEnable() = runBlocking {
        val entry = entry("ticketmaster")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "private-test-key", 10, 50)
        registry.setEnabled(entry, true)
        registry.configure(entry, "", "", 10, 50, clearKey = true)
        assertFalse(registry.state.value.getValue(entry.id).enabled)
        assertFalse(registry.state.value.getValue(entry.id).ready(entry))
        assertTrue(vault.records.isEmpty())
        registry.configure(entry, "", "another-key", 10, 50)
        vault.records.clear()
        assertTrue(runCatching { registry.setEnabled(entry, true) }.isFailure)
    }

    @Test fun requestAllowanceAndCooldownSurviveReload() = runBlocking {
        val entry = entry("refuge")
        val registry = registry()
        registry.install(entry)
        registry.configure(entry, "", "", 10, 1)
        registry.setEnabled(entry, true)
        val now = System.currentTimeMillis()
        registry.reserve(entry, now)
        assertTrue(runCatching { registry.reserve(entry, now) }.isFailure)
        assertTrue(runCatching { registry().reserve(entry, now + 2000) }.isFailure)
        assertEquals(1, registry.state.value.getValue(entry.id).usageCount)
    }

    @Test fun everyNativeAdapterHasSchemaRequestAndCorrectAuth() {
        val entries = (GitHubMarketplaceCatalog.packages + GitHubMarketplaceCatalog.legacyPackages).filter { it.runtime == MarketplaceRuntime.NATIVE }
        assertEquals(NativeMarketplaceCatalog.operations.keys, entries.map { it.provider }.toSet())
        entries.forEach { entry ->
            NativeMarketplaceCatalog.operations.getValue(entry.provider).forEach { (operation, fields) ->
                val args = buildJsonObject {
                    fields.forEach { name ->
                        when {
                            name.endsWith("latitude") -> put(name, 43.65)
                            name.endsWith("longitude") -> put(name, -79.38)
                            name == "organization_id" -> put(name, "123")
                            name == "resource_id" -> put(name, "resource-123")
                            else -> put(name, "Toronto")
                        }
                    }
                }
                val key = if (NativeMarketplaceCatalog.requiresKey(entry)) "test-key" else ""
                val config = NativeProviderConfiguration(NativePluginInstallation(endpoint = "https://managed.example/search", endpoints = mapOf("geocode" to "https://managed.example/search", "restrooms" to "https://managed.example/interpreter")), key)
                val request = NativeMarketplaceRequests.build(entry, operation, args, config)
                assertTrue(request.url.isHttps)
                assertTrue(NativeMarketplaceCatalog.definitions(entry).any { it.name.endsWith("__$operation") })
                if (key.isNotEmpty()) {
                    assertTrue(request.url.toString().contains(key) || request.header("Authorization")?.contains(key) == true || request.header("X-Goog-Api-Key") == key)
                    assertTrue(runCatching { NativeMarketplaceRequests.build(entry, operation, args, config.copy(apiKey = "")) }.isFailure)
                } else {
                    assertEquals(null, request.header("Authorization"))
                }
            }
        }
    }

    @Test fun managedEndpointsAndArgumentsRejectUnsafeOrGuessedInputs() {
        listOf("http://managed.example/search", "https://nominatim.openstreetmap.org/search", "https://overpass-api.de/api/interpreter", "https://user:pass@managed.example/search", "https://managed.example/search?key=secret").forEach {
            assertFalse(NativeMarketplaceCatalog.validEndpoint(it))
        }
        assertTrue(NativeMarketplaceCatalog.validEndpoint("https://managed.example/search"))
        listOf(
            buildJsonObject {
                put("latitude", "43.65")
                put("longitude", -79.38)
            },
            buildJsonObject {
                put("latitude", 100)
                put("longitude", -79.38)
            },
            buildJsonObject {
                put("latitude", 43.65)
                put("longitude", -79.38)
                put("endpoint", "https://evil.example")
            }
        ).forEach { assertTrue(runCatching { NativeMarketplaceCatalog.validate("refuge", "restrooms", it) }.isFailure) }
        assertFalse(NativeMarketplaceCatalog.validKey("YOUR_API_KEY"))
        assertFalse(NativeMarketplaceCatalog.validKey("key\nheader"))
    }

    private class MemoryVault : SecretVault {
        val records = mutableMapOf<String, ByteArray>()
        var failDelete = false
        override suspend fun put(secretRef: String, secret: ByteArray) {
            records[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = records[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            if (failDelete) throw IOException("vault unavailable")
            records.remove(secretRef)
        }
    }
}
