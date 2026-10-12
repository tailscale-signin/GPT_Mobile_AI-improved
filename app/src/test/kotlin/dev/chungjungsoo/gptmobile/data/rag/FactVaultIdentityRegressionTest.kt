package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactVaultIdentityRegressionTest {
    @Test
    fun `adding a former value cannot overwrite an edited memory`() = runBlocking {
        val storage = MemoryVault()
        val repository = FactVaultRepository(storage, KnowledgeGraphEngine())
        repository.saveManual("I use Blender")
        val originalId = repository.state.value.facts.single().id
        repository.pin(originalId, true)
        repository.saveManual("I use Maya", originalId)
        val edited = repository.state.value.facts.single()

        repository.saveManual("I use Blender")

        val restored = FactVaultRepository(storage, KnowledgeGraphEngine())
        restored.load()
        val facts = restored.state.value.facts
        assertEquals(2, facts.size)
        assertEquals(2, facts.map { it.id }.toSet().size)
        assertEquals(edited, facts.single { it.id == originalId })
        assertTrue(facts.single { it.fact.target.name == "I use Blender" }.id != originalId)
        assertEquals(2, facts.single { it.id == originalId }.revision)
        assertEquals(listOf("I use Blender"), facts.single { it.id == originalId }.previousValues)
    }

    @Test
    fun `adding an edited current value rejects a case insensitive duplicate`() = runBlocking {
        val repository = FactVaultRepository(MemoryVault(), KnowledgeGraphEngine())
        repository.saveManual("I use Blender")
        val id = repository.state.value.facts.single().id
        repository.saveManual("I use Maya", id)
        val before = repository.state.value

        assertTrue(runCatching { repository.saveManual("  I USE MAYA  ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.state.value)
    }

    @Test
    fun `duplicate addition cannot reenable or unpin an existing record`() = runBlocking {
        val repository = FactVaultRepository(MemoryVault(), KnowledgeGraphEngine())
        repository.saveManual("A saved preference")
        val id = repository.state.value.facts.single().id
        repository.pin(id, true)
        repository.setFactEnabled(id, false)
        val before = repository.state.value

        assertTrue(runCatching { repository.saveManual("A saved preference") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.state.value)
        assertFalse(repository.state.value.facts.single().enabled)
        assertTrue(repository.state.value.facts.single().pinned)
    }

    @Test
    fun `editing into another memory fails without changing either record`() = runBlocking {
        val repository = FactVaultRepository(MemoryVault(), KnowledgeGraphEngine())
        repository.saveManual("First memory")
        repository.saveManual("Second memory")
        val secondId = repository.state.value.facts.single { it.fact.target.name == "Second memory" }.id
        val before = repository.state.value

        assertTrue(runCatching { repository.saveManual("First memory", secondId) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.state.value)
    }

    @Test
    fun `identical text remains independent across memory scopes`() = runBlocking {
        val repository = FactVaultRepository(MemoryVault(), KnowledgeGraphEngine())
        repository.saveManual("Shared wording", scope = "personal")
        repository.saveManual("Shared wording", scope = "project:one")
        repository.saveManual("Shared wording", scope = "personal:branch:two")

        assertEquals(3, repository.state.value.facts.size)
        assertEquals(3, repository.state.value.facts.map { it.id }.toSet().size)
        assertEquals(setOf("personal", "project:one", "personal:branch:two"), repository.state.value.facts.map { it.scope }.toSet())
    }

    @Test
    fun `failed collision save leaves the edited record durable`() = runBlocking {
        val storage = MemoryVault()
        val repository = FactVaultRepository(storage, KnowledgeGraphEngine())
        repository.saveManual("Original wording")
        val id = repository.state.value.facts.single().id
        repository.saveManual("Corrected wording", id)
        val before = repository.state.value
        storage.failWrites = true

        assertTrue(runCatching { repository.saveManual("Original wording") }.isFailure)
        assertEquals(before, repository.state.value)
        storage.failWrites = false
        val restored = FactVaultRepository(storage, KnowledgeGraphEngine())
        restored.load()
        assertEquals(before, restored.state.value)
    }

    @Test
    fun `tool visibility excludes pending and superseded records even when enabled`() = runBlocking {
        val storage = MemoryVault()
        val repository = FactVaultRepository(storage, KnowledgeGraphEngine())
        repository.setEnabled(true)
        repository.saveManual("Retained fact")
        val active = repository.state.value.facts.single()
        val snapshot = repository.state.value.copy(
            facts = listOf(
                active,
                active.copy(id = "pending", awaitingReview = true),
                active.copy(id = "superseded", supersededBy = active.id)
            )
        )
        storage.put(FactVaultRepository.VAULT_REFERENCE, Json.encodeToString(FactVaultSnapshot.serializer(), snapshot).encodeToByteArray())

        val restored = FactVaultRepository(storage, KnowledgeGraphEngine())
        assertEquals(listOf(active.id), restored.visibleFacts(1, isLocal = true).map { it.id })
    }

    private class MemoryVault : SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        var failWrites = false

        override suspend fun put(secretRef: String, secret: ByteArray) {
            check(!failWrites) { "Storage unavailable" }
            values[secretRef] = secret.copyOf()
        }

        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()

        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }
}
