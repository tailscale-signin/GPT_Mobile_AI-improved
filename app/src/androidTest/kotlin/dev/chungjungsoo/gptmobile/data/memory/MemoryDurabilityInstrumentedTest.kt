package dev.chungjungsoo.gptmobile.data.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.memory.v2.AesGcmMemoryCipher
import dev.chungjungsoo.gptmobile.data.memory.v2.MemoryRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import dev.chungjungsoo.gptmobile.data.rag.MemoryLearning
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDurabilityInstrumentedTest {
    @Test fun encryptedCutoverPreservesIdentityAndRejectsStaleWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabaseV2::class.java).build()
        try {
            val cipher = AesGcmMemoryCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            val repository = MemoryRepository(db, cipher)
            val fact = VaultFact("stable", MemoryLearning.observation("Synthetic private fact"), revision = 7, pinned = true)
            val original = FactVaultSnapshot(enabled = true, facts = listOf(fact), suppressedIds = setOf("deleted"))
            assertEquals(original, repository.importLegacy(original))
            assertEquals(original, repository.read())
            assertFalse(db.memoryV2Dao().fact("stable")!!.payload.decodeToString().contains("Synthetic private fact"))
            val changed = original.copy(facts = listOf(fact.copy(revision = 8, localOnly = true)))
            repository.commit(changed, original)
            assertTrue(runCatching { repository.commit(original, original) }.isFailure)
            assertEquals(changed, repository.read())
        } finally {
            db.close()
        }
    }

    @Test fun portableRestoreUsesNewKeyAndCannotResurrectDeletedFacts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabaseV2::class.java).build()
        val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabaseV2::class.java).build()
        fun cipher() = AesGcmMemoryCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        try {
            val repository = MemoryRepository(db, cipher())
            val original = FactVaultSnapshot(facts = listOf(VaultFact("stable", MemoryLearning.observation("Portable note"))))
            repository.importLegacy(original)
            val archive = repository.exportPortable()
            val destination = MemoryRepository(fresh, cipher())
            destination.restorePortable(archive, null)
            assertEquals(original, destination.read())
            val removed = original.copy(facts = emptyList(), suppressedIds = setOf("stable"))
            destination.commit(removed, original)
            destination.restorePortable(archive, removed)
            assertTrue(destination.read()!!.facts.isEmpty())
            assertTrue("stable" in destination.read()!!.suppressedIds)
            assertTrue(runCatching { destination.restorePortable(mapOf("memory-portable-000001" to "bad"), removed) }.isFailure)
            assertTrue(destination.read()!!.facts.isEmpty())
        } finally {
            db.close()
            fresh.close()
        }
    }
}
