package dev.chungjungsoo.gptmobile.data.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretVaultInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val suffix = UUID.randomUUID().toString()
    private val keyAlias = "gpt-mobile-test-$suffix"
    private val directoryName = "secret-vault-test-$suffix"
    private val vault = AndroidSecretVault(context, keyAlias, directoryName)

    @After
    fun cleanUp() {
        File(context.noBackupFilesDir, directoryName).deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun roundTripOverwriteAndDelete() = runBlocking {
        val secretRef = "profile_123"
        val first = "first-secret".encodeToByteArray()
        val second = "replacement-secret".encodeToByteArray()

        vault.put(secretRef, first)
        val firstRevision = vault.recordRevision(secretRef)
        assertNotNull(firstRevision)
        assertTrue(requireNotNull(firstRevision).matches(Regex("[a-f0-9]{64}")))
        assertEquals(firstRevision, AndroidSecretVault(context, keyAlias, directoryName).recordRevision(secretRef))

        assertArrayEquals(first, vault.read(secretRef))
        assertTrue(File(context.noBackupFilesDir, "$directoryName/$secretRef.vault").isFile)
        assertFalse(File(context.filesDir, "$directoryName/$secretRef.vault").exists())

        vault.put(secretRef, second)
        assertArrayEquals(second, vault.read(secretRef))
        val secondRevision = vault.recordRevision(secretRef)
        assertNotEquals(firstRevision, secondRevision)

        // The identity follows the encrypted record, including a same-value replacement.
        vault.put(secretRef, second)
        assertNotEquals(secondRevision, vault.recordRevision(secretRef))

        vault.delete(secretRef)
        assertNull(vault.read(secretRef))
        assertNull(vault.recordRevision(secretRef))
    }

    @Test
    fun rejectsUnsafeReferencesAndOversizedSecrets() = runBlocking {
        assertFails<IllegalArgumentException> { vault.put("../secret", byteArrayOf(1)) }
        assertFails<IllegalArgumentException> { vault.read("") }
        assertFails<IllegalArgumentException> { vault.recordRevision("../secret") }
        assertFails<IllegalArgumentException> {
            vault.put("profile_oversized", ByteArray(AndroidSecretVault.MAX_SECRET_BYTES + 1))
        }
    }

    @Test
    fun rejectsMalformedRecords() = runBlocking {
        val secretRef = "profile_corrupt"
        val record = File(context.noBackupFilesDir, "$directoryName/$secretRef.vault")
        record.parentFile?.mkdirs()
        record.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertFails<SecretVaultException> { vault.read(secretRef) }
        assertFails<SecretVaultException> { vault.recordRevision(secretRef) }
    }

    @Test
    fun oversizedRecordIsRejectedBeforeAllocationAndSizeDoesNotRequireAKey() = runBlocking {
        val secretRef = "oversized_record"
        val record = File(context.noBackupFilesDir, "$directoryName/$secretRef.vault")
        record.parentFile?.mkdirs()
        java.io.RandomAccessFile(record, "rw").use { it.setLength(32L * 1024 * 1024) }
        assertTrue(vault.storedSize(secretRef) == 32L * 1024 * 1024)
        assertFails<SecretVaultException> { vault.read(secretRef) }
        assertFails<SecretVaultException> { vault.recordRevision(secretRef) }
        assertTrue(record.exists())
    }

    @Test
    fun rejectsRecordMovedToAnotherReference() = runBlocking {
        val firstRef = "profile_first"
        val secondRef = "profile_second"
        vault.put(firstRef, "first-secret".encodeToByteArray())
        vault.put(secondRef, "second-secret".encodeToByteArray())

        val directory = File(context.noBackupFilesDir, directoryName)
        File(directory, "$secondRef.vault").copyTo(File(directory, "$firstRef.vault"), overwrite = true)

        assertFails<SecretVaultException> { vault.read(firstRef) }
    }

    @Test
    fun missingKeystoreKey_preservesEncryptedRecord() = runBlocking {
        val secretRef = "profile_missing_key"
        vault.put(secretRef, "secret".encodeToByteArray())
        val revision = vault.recordRevision(secretRef)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

        assertEquals(revision, AndroidSecretVault(context, keyAlias, directoryName).recordRevision(secretRef))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias))
        assertFails<SecretVaultException> { vault.read(secretRef) }
        assertTrue(File(context.noBackupFilesDir, "$directoryName/$secretRef.vault").exists())
    }

    @Test
    fun revisionWithoutStoredRecordDoesNotCreateKeyOrDirectory() = runBlocking {
        assertNull(vault.recordRevision("not_stored"))
        assertFalse(File(context.noBackupFilesDir, directoryName).exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias))
    }

    private suspend inline fun <reified T : Throwable> assertFails(crossinline block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("Expected ${T::class.java.simpleName}, got ${thrown?.javaClass?.simpleName}", thrown is T)
    }
}
