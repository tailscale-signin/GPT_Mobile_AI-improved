package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyModeStoreTest {
    @Test
    fun restoringTheSamePrivacyChoicesDoesNotRestoreAnEarlierAuthorization() {
        val fixture = Fixture()
        val original = fixture.store.snapshot()
        fixture.store.setEnabled(true)
        fixture.store.setEnabled(false)
        fixture.store.setCategory(PrivacyCategory.FINANCES, false)
        fixture.store.setCategory(PrivacyCategory.FINANCES, true)
        val restored = fixture.store.snapshot()

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.categories, restored.categories)
        assertTrue(restored.revision > original.revision)
        assertNotEquals(original, restored)
        assertEquals(restored, fixture.store.state.value)
        assertEquals(restored, fixture.recreate().snapshot())
    }

    @Test
    fun privateTermWriteInvalidatesBothBoundariesAndReadersWaitForCompletion() = runTest {
        val fixture = Fixture()
        val before = fixture.store.snapshot()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.vault.writeGate = releaseWrite
        val writer = launch { fixture.store.savePrivateTerms("new private term\n\n") }
        runCurrent()
        val during = fixture.store.snapshot()
        assertTrue(during.revision > before.revision)

        val reader = async { fixture.store.privateTerms() }
        runCurrent()
        assertFalse(reader.isCompleted)
        assertEquals(0, fixture.vault.reads)

        releaseWrite.complete(Unit)
        writer.join()
        assertEquals(listOf("new private term"), reader.await())
        val after = fixture.store.snapshot()
        assertTrue(after.revision > during.revision)
        assertEquals(after, fixture.store.state.value)
        assertTrue(fixture.vault.lastWriteBuffer!!.all { it == 0.toByte() })
        assertTrue(fixture.vault.lastReadBuffer!!.all { it == 0.toByte() })
        assertTrue(fixture.values.values.none { it is String || it is ByteArray })
    }

    @Test
    fun cancelledPrivateTermWriteInvalidatesPendingSnapshotsAndReleasesReaders() = runTest {
        val fixture = Fixture()
        fixture.vault.writeGate = CompletableDeferred()
        val writer = launch { fixture.store.savePrivateTerms("cancelled private term") }
        runCurrent()
        val during = fixture.store.snapshot()
        val reader = async { fixture.store.privateTerms() }
        runCurrent()
        assertFalse(reader.isCompleted)

        writer.cancelAndJoin()

        assertTrue(fixture.store.snapshot().revision > during.revision)
        assertEquals(listOf("original private term"), reader.await())
        assertTrue(fixture.vault.lastWriteBuffer!!.all { it == 0.toByte() })
    }

    @Test
    fun failedPrivateTermWriteInvalidatesSnapshotsEvenIfTheVaultAppliedTheWrite() = runTest {
        for (applyBeforeFailure in listOf(false, true)) {
            val fixture = Fixture()
            val releaseWrite = CompletableDeferred<Unit>()
            fixture.vault.writeGate = releaseWrite
            fixture.vault.failWrite = true
            fixture.vault.applyBeforeFailure = applyBeforeFailure
            val writer = async { runCatching { fixture.store.savePrivateTerms("replacement private term") } }
            runCurrent()
            val during = fixture.store.snapshot()
            val reader = async { fixture.store.privateTerms() }
            runCurrent()
            assertFalse(reader.isCompleted)

            releaseWrite.complete(Unit)

            assertTrue(writer.await().exceptionOrNull() is IOException)
            assertTrue(fixture.store.snapshot().revision > during.revision)
            assertEquals(listOf(if (applyBeforeFailure) "replacement private term" else "original private term"), reader.await())
            assertTrue(fixture.vault.lastWriteBuffer!!.all { it == 0.toByte() })
        }
    }

    @Test
    fun aReadAlreadyInProgressCompletesBeforeTheNextWriteAndItsSnapshotBecomesStale() = runTest {
        val fixture = Fixture()
        val captured = fixture.store.snapshot()
        val releaseRead = CompletableDeferred<Unit>()
        fixture.vault.readGate = releaseRead
        val reader = async { fixture.store.privateTerms() }
        runCurrent()
        val writer = launch { fixture.store.savePrivateTerms("replacement private term") }
        runCurrent()
        assertEquals(0, fixture.vault.writes)

        releaseRead.complete(Unit)

        assertEquals(listOf("original private term"), reader.await())
        writer.join()
        assertNotEquals(captured, fixture.store.snapshot())
        assertEquals(listOf("replacement private term"), fixture.store.privateTerms())
    }

    @Test
    fun failedRevisionCommitPreventsTheVaultWriteAndInvalidatesTheObservedSnapshot() = runTest {
        val fixture = Fixture()
        val before = fixture.store.snapshot()
        fixture.failNextCommit = true

        val result = runCatching { fixture.store.savePrivateTerms("replacement private term") }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, fixture.vault.writes)
        assertNotEquals(before, fixture.store.snapshot())
        assertEquals(fixture.store.snapshot(), fixture.store.state.value)
        assertEquals(listOf("original private term"), fixture.store.privateTerms())
    }

    private class Fixture {
        val values = mutableMapOf<String, Any>()
        var failNextCommit = false
        val vault = ControlledVault()
        private val preferences = mockk<SharedPreferences>()
        private val context = mockk<Context>()
        private val verifier = mockk<PrivacyProviderVerifier>()

        init {
            every { context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
            every { preferences.getBoolean(any(), any()) } answers { values[firstArg<String>()] as? Boolean ?: secondArg() }
            every { preferences.getLong(any(), any()) } answers { values[firstArg<String>()] as? Long ?: secondArg() }
            every { preferences.edit() } answers {
                val pending = mutableMapOf<String, Any>()
                val editor = mockk<SharedPreferences.Editor>()
                every { editor.putBoolean(any(), any()) } answers {
                    pending[firstArg()] = secondArg<Boolean>()
                    editor
                }
                every { editor.putLong(any(), any()) } answers {
                    pending[firstArg()] = secondArg<Long>()
                    editor
                }
                every { editor.commit() } answers {
                    values.putAll(pending)
                    (!failNextCommit).also { failNextCommit = false }
                }
                editor
            }
        }

        val store = recreate()
        fun recreate() = PrivacyModeStore(context, verifier, vault)
    }

    private class ControlledVault : SecretVault {
        private var stored = "original private term".encodeToByteArray()
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrite = false
        var applyBeforeFailure = false
        var reads = 0
        var writes = 0
        var lastReadBuffer: ByteArray? = null
        var lastWriteBuffer: ByteArray? = null

        override suspend fun read(secretRef: String): ByteArray {
            assertEquals("privacy_custom_terms", secretRef)
            reads++
            readGate?.await()
            return stored.copyOf().also { lastReadBuffer = it }
        }

        override suspend fun put(secretRef: String, secret: ByteArray) {
            assertEquals("privacy_custom_terms", secretRef)
            writes++
            lastWriteBuffer = secret
            writeGate?.await()
            if (!failWrite || applyBeforeFailure) stored = secret.copyOf()
            if (failWrite) throw IOException("Simulated vault write failure")
        }

        override suspend fun delete(secretRef: String) = error("No deletion expected")
    }
}
