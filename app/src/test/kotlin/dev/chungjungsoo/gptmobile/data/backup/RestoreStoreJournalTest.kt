package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreStoreJournalTest {
    private class Vault : SecretVault {
        val records = mutableMapOf<String, ByteArray>()
        var failAfter: Int? = null
        var writes = 0
        override suspend fun put(secretRef: String, secret: ByteArray) {
            if (failAfter == writes++) error("simulated process death")
            records[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = records[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            records.remove(secretRef)
        }
        override suspend fun references() = records.keys.toSet()
    }
    private fun snapshot() = RestoreStoreSnapshot(
        "txn",
        CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS)),
        mapOf("theme" to BackupValue("string", "a".repeat(100000))),
        emptyMap(),
        mapOf("key" to "old"),
        BackupProtection(true),
        "password"
    )

    @Test fun restartRecoversAllStoresAndRetainsJournalUntilExplicitCommitCleanup() = runBlocking {
        val vault = Vault()
        val expected = snapshot()
        RestoreStoreJournal(vault).write(expected)
        assertEquals(expected, RestoreStoreJournal(vault).read())
        assertEquals(expected, RestoreStoreJournal(vault).read())
        assertTrue(vault.records.values.all { it.size <= 64 * 1024 })
        RestoreStoreJournal(vault).clear()
        assertNull(RestoreStoreJournal(vault).read())
        assertTrue(vault.records.isEmpty())
    }

    @Test fun incompleteJournalIsNeverPublished() = runBlocking {
        for (boundary in 0..2) {
            val vault = Vault().apply { failAfter = boundary }
            assertTrue(runCatching { RestoreStoreJournal(vault).write(snapshot()) }.isFailure)
            assertNull(RestoreStoreJournal(vault).read())
        }
    }
}
