package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalToolRegistryTest {
    private val definition = AgentToolDefinition("read_repository", "Read source", JsonObject(emptyMap()), setOf(AgentToolCapability.GITHUB))

    @Test fun registryReusesOnlyMatchingLiveSchemasAndAuthority() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val first = UniversalToolRegistry(store).prepare("profile:chat", "policy-1", listOf(definition), emptyMap(), true, 100)
        assertTrue(first.persisted)
        assertEquals(0, first.reusedSchemas)
        val restarted = UniversalToolRegistry(store)
        val next = restarted.prepare("profile:chat", "policy-1", listOf(definition), emptyMap(), true, 200)
        assertEquals(first.digest, next.digest)
        assertEquals(1, next.reusedSchemas)
        assertEquals(0, restarted.prepare("profile:chat", "revoked-and-replaced", listOf(definition), emptyMap(), true, 300).reusedSchemas)
        assertEquals(0, restarted.prepare("other-profile:chat", "policy-1", listOf(definition), emptyMap(), true, 300).reusedSchemas)
    }

    @Test fun removalAndSchemaChangesReplaceInventoryWithoutRevivingCachedTools() = runBlocking {
        val registry = UniversalToolRegistry(GitHubRepositoryStateStore())
        val first = registry.prepare("profile", "policy", listOf(definition), emptyMap(), true, 100)
        val changed = registry.prepare("profile", "policy", listOf(definition.copy(description = "A new complete declaration")), emptyMap(), true, 200)
        assertEquals(0, changed.reusedSchemas)
        assertNotEquals(first.digest, changed.digest)
        val removed = registry.prepare("profile", "policy", emptyList(), emptyMap(), true, 300)
        assertEquals(0, removed.toolCount)
        assertNotEquals(first.digest, removed.digest)
        assertEquals(0, registry.prepare("profile", "policy", listOf(definition), emptyMap(), true, 400).reusedSchemas)
    }

    @Test fun temporarySnapshotsCannotRestoreOrOverwritePersistentInventory() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val registry = UniversalToolRegistry(store)
        val first = registry.prepare("profile", "policy", listOf(definition), emptyMap(), true, 100)
        val temporary = registry.prepare("profile", "policy", emptyList(), emptyMap(), false, 200)
        assertFalse(temporary.persisted)
        assertEquals(first.digest, UniversalToolRegistry(store).prepare("profile", "policy", listOf(definition), emptyMap(), true, 300).digest)
    }

    @Test fun expiredMetadataAndDifferentWireSchemasCannotReuseCosts() = runBlocking {
        val registry = UniversalToolRegistry(GitHubRepositoryStateStore())
        registry.prepare("profile", "policy", listOf(definition), emptyMap(), true, 100)
        assertEquals(0, registry.prepare("profile", "policy", listOf(definition), emptyMap(), true, 100 + 24 * 60 * 60_000L + 1).reusedSchemas)
        val small = UniversalToolRegistry.schemaTokens(definition)
        val large = UniversalToolRegistry.schemaTokens(definition.copy(description = "long declaration ".repeat(100)))
        assertTrue(large > small)
    }
}
