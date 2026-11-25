package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyModeStoreTest {
    @After
    fun resetApplicationPrivacyState() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("privacy_mode_enabled", false) } returns false
        PrivacyModeStore.initialize(context)
    }

    @Test
    fun verifiedRetentionSurvivesRestartAndInvalidatesPreparedPrivateDispatch() {
        val fixture = Fixture()
        val initial = fixture.store.effectiveSnapshot(true)
        assertFalse(initial.requireVerifiedRetention)
        fixture.store.requestVerifiedRetention(true)
        val strict = fixture.store.effectiveSnapshot(true)
        assertTrue(strict.requireVerifiedRetention)
        assertTrue(strict.revision > initial.revision)
        fixture.awaitUpdates()
        assertEquals(strict, fixture.recreate().effectiveSnapshot(true))
        fixture.store.requestVerifiedRetention(false)
        fixture.awaitUpdates()
        val restored = fixture.recreate().effectiveSnapshot(true)
        assertFalse(restored.requireVerifiedRetention)
        assertTrue(restored.revision > strict.revision)
    }

    @Test
    fun savingUnchangedMaskAndRetentionChoicesKeepsThePreparedRevision() {
        val fixture = Fixture()
        val prepared = fixture.store.effectiveSnapshot(true)
        fixture.store.requestCategory(PrivacyCategory.LOCATIONS, true)
        fixture.store.requestVerifiedRetention(false)
        fixture.awaitUpdates()
        assertEquals(prepared, fixture.store.effectiveSnapshot(true))
        assertEquals(prepared, fixture.recreate().effectiveSnapshot(true))
    }

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

    @Test
    fun snapshotRemainsResponsiveAfterMemoryChangesWhileDiskCommitIsBlocked() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.afterMemoryCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val saving = workers.submit { fixture.store.setEnabled(true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val reading = workers.submit<PrivacyModeState> { fixture.store.snapshot() }
            val observed = reading.get(2, TimeUnit.SECONDS)

            assertTrue(observed.enabled)
            assertEquals(1L, observed.revision)
            assertEquals(PrivacyCategory.entries.toSet(), observed.categories)
            assertFalse(saving.isDone)
            release.countDown()
            saving.get(5, TimeUnit.SECONDS)
            assertEquals(observed, fixture.store.state.value)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun repeatingTheSameMasterChoiceAfterFailedCommitRetriesPersistence() {
        val fixture = Fixture()
        fixture.failNextCommit = true

        val failure = runCatching { fixture.store.setEnabled(true) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertTrue(fixture.store.snapshot().enabled)
        assertTrue(fixture.persistedValues.isEmpty())

        fixture.store.setEnabled(true)

        assertEquals(2, fixture.commits.get())
        assertEquals(true, fixture.persistedValues["privacy_mode_enabled"])
        assertEquals(2L, fixture.persistedValues["privacy_revision"])
        assertEquals(fixture.store.snapshot(), fixture.store.state.value)
    }

    @Test
    fun repeatingTheSameCategoryChoiceAfterFailedCommitRetriesPersistence() {
        val fixture = Fixture()
        fixture.failNextCommit = true

        val failure = runCatching { fixture.store.setCategory(PrivacyCategory.LOCATIONS, false) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertFalse(PrivacyCategory.LOCATIONS in fixture.store.snapshot().categories)
        assertTrue(fixture.persistedValues.isEmpty())

        fixture.store.setCategory(PrivacyCategory.LOCATIONS, false)

        assertEquals(2, fixture.commits.get())
        assertEquals(false, fixture.persistedValues["privacy_category_LOCATIONS"])
        assertEquals(2L, fixture.persistedValues["privacy_revision"])
    }

    @Test
    fun asynchronousChangesStayResponsiveAndKeepTheLastChoiceAcrossControls() {
        val fixture = Fixture()
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.afterMemoryCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val submitting = Executors.newSingleThreadExecutor()
        try {
            fixture.store.requestEnabled(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            submitting.submit {
                fixture.store.requestEnabled(false)
                fixture.store.requestCategory(PrivacyCategory.LOCATIONS, false)
            }.get(2, TimeUnit.SECONDS)
            assertEquals(3, fixture.store.updates.value.pending)
            release.countDown()
            fixture.awaitUpdates()

            assertFalse(fixture.store.snapshot().enabled)
            assertFalse(PrivacyCategory.LOCATIONS in fixture.store.snapshot().categories)
            assertEquals(3, fixture.commits.get())
            assertTrue(fixture.commitThreads.all { it != caller })
            assertFalse(fixture.store.updates.value.failed)
            assertEquals(false, fixture.persistedValues["privacy_mode_enabled"])
            assertEquals(false, fixture.persistedValues["privacy_category_LOCATIONS"])
        } finally {
            release.countDown()
            submitting.shutdownNow()
        }
    }

    @Test
    fun retrySavingRecoversAnAsynchronouslyFailedCommitWithoutChangingTheChoice() {
        val fixture = Fixture()
        fixture.failNextCommit = true
        fixture.store.requestEnabled(true)
        fixture.awaitUpdates()
        assertTrue(fixture.store.updates.value.failed)
        assertTrue(fixture.store.snapshot().enabled)
        assertTrue(fixture.persistedValues.isEmpty())

        fixture.store.retrySaving()
        fixture.awaitUpdates()

        assertEquals(2, fixture.commits.get())
        assertTrue(fixture.store.snapshot().enabled)
        assertEquals(true, fixture.persistedValues["privacy_mode_enabled"])
        assertEquals(PrivacySettingsSaveState(), fixture.store.updates.value)
    }

    @Test
    fun queuedAvailabilityIsImmediatelyVisibleToSettingsAndAuditButDoesNotActivateAConversation() {
        val fixture = Fixture()
        fixture.initializeActivePrivacy()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.afterMemoryCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            fixture.store.requestCategory(PrivacyCategory.CONTACTS, false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = fixture.store.snapshot()
            assertEquals(before, PrivacyModeStore.auditPolicySnapshot())

            fixture.store.requestEnabled(true)
            val enabled = fixture.store.snapshot()
            assertTrue(enabled.enabled)
            assertTrue(enabled.revision > before.revision)
            assertFalse(PrivacyModeStore.active())
            assertEquals(enabled, fixture.store.state.value)
            assertEquals(enabled, PrivacyModeStore.auditPolicySnapshot())
            assertFalse(fixture.values["privacy_mode_enabled"] as Boolean)

            fixture.store.requestCategory(PrivacyCategory.LOCATIONS, false)
            val categorized = fixture.store.snapshot()
            assertFalse(PrivacyCategory.LOCATIONS in categorized.categories)
            assertFalse(PrivacyCategory.CONTACTS in categorized.categories)
            assertTrue(categorized.revision > enabled.revision)
            assertEquals(1, fixture.commits.get())
            assertEquals(categorized, PrivacyModeStore.auditPolicySnapshot())

            fixture.store.requestEnabled(false)
            val disabled = fixture.store.snapshot()
            assertFalse(disabled.enabled)
            assertTrue(disabled.revision > categorized.revision)
            assertFalse(PrivacyModeStore.active())
            assertEquals(disabled, fixture.store.state.value)
            assertEquals(disabled, PrivacyModeStore.auditPolicySnapshot())
        } finally {
            release.countDown()
            fixture.awaitUpdates()
        }
        assertEquals(fixture.store.snapshot(), PrivacyModeStore.auditPolicySnapshot())
    }

    @Test
    fun auditRetainsTheEffectivePolicyAfterPersistenceFailsBeforeChangingPreferences() {
        val fixture = Fixture()
        fixture.initializeActivePrivacy()
        val original = fixture.store.snapshot()
        fixture.beforeMemoryCommit = { throw IOException("Simulated preference write failure") }

        fixture.store.requestEnabled(true)
        fixture.awaitUpdates()
        val enabled = fixture.store.snapshot()
        assertTrue(fixture.store.updates.value.failed)
        assertFalse(PrivacyModeStore.active())
        assertTrue(enabled.revision > original.revision)
        assertEquals(enabled, PrivacyModeStore.auditPolicySnapshot())

        fixture.store.requestCategory(PrivacyCategory.LOCATIONS, false)
        fixture.awaitUpdates()
        val categorized = fixture.store.snapshot()
        assertFalse(PrivacyCategory.LOCATIONS in categorized.categories)
        assertTrue(categorized.revision > enabled.revision)
        assertEquals(categorized, PrivacyModeStore.auditPolicySnapshot())
        assertTrue(fixture.values.isEmpty())
        assertTrue(fixture.persistedValues.isEmpty())

        fixture.beforeMemoryCommit = null
        fixture.store.retrySaving()
        fixture.awaitUpdates()

        assertEquals(PrivacySettingsSaveState(), fixture.store.updates.value)
        assertEquals(categorized, fixture.store.snapshot())
        assertEquals(categorized, PrivacyModeStore.auditPolicySnapshot())
        assertEquals(true, fixture.persistedValues["privacy_mode_enabled"])
        assertEquals(false, fixture.persistedValues["privacy_category_LOCATIONS"])
        assertEquals(categorized.revision, fixture.persistedValues["privacy_revision"])
    }

    @Test
    fun oldPersistenceCompletionCannotRevertANewerRequestedChoice() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.afterMemoryCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val observing = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            fixture.store.requestEnabled(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.store.requestEnabled(false)
            val latest = fixture.store.snapshot()
            assertFalse(latest.enabled)
            val reverted = AtomicBoolean()
            observing.launch { fixture.store.state.collect { if (it.enabled) reverted.set(true) } }

            release.countDown()
            fixture.awaitUpdates()

            assertFalse(reverted.get())
            assertFalse(fixture.store.snapshot().enabled)
            assertTrue(fixture.store.snapshot().revision >= latest.revision)
            assertEquals(false, fixture.persistedValues["privacy_mode_enabled"])
        } finally {
            release.countDown()
            observing.cancel()
        }
    }

    @Test
    fun optionsAvailabilityNeverChangesExistingConversationProtectionOrPreparedRevision() {
        val fixture = Fixture()
        val private = ConversationPrivacyContext(51, true)
        val ordinary = ConversationPrivacyContext(52, false)
        val original = ConversationPrivacyContext.withCaptured(private) { fixture.store.effectiveSnapshot() }
        assertTrue(original.enabled)
        fixture.store.setEnabled(true)
        fixture.store.setEnabled(false)
        val current = ConversationPrivacyContext.withCaptured(private) {
            assertTrue(PrivacyModeStore.active())
            fixture.store.effectiveSnapshot()
        }
        assertEquals(original, current)
        assertEquals(original, fixture.recreate().effectiveSnapshot(true))
        ConversationPrivacyContext.withCaptured(ordinary) {
            assertFalse(fixture.store.effectiveSnapshot().enabled)
            PrivacyModeStore.requireUnprotectedTransport()
        }
    }

    @Test
    fun maskingChangesStillInvalidatePreparedPrivateRequestsWhenFeatureAvailabilityIsOff() = runTest {
        val fixture = Fixture()
        val prepared = fixture.store.effectiveSnapshot(true)
        fixture.store.setCategory(PrivacyCategory.LOCATIONS, false)
        val categorized = fixture.store.effectiveSnapshot(true)
        assertFalse(fixture.store.snapshot().enabled)
        assertTrue(categorized.enabled)
        assertFalse(PrivacyCategory.LOCATIONS in categorized.categories)
        assertTrue(categorized.protectionRevision > prepared.protectionRevision)
        fixture.store.savePrivateTerms("a new private term")
        val updatedTerms = fixture.store.effectiveSnapshot(true)
        assertTrue(updatedTerms.protectionRevision > categorized.protectionRevision)
        assertNotEquals(categorized, updatedTerms)
    }

    private class Fixture {
        val values: MutableMap<String, Any> = Collections.synchronizedMap(mutableMapOf())
        val persistedValues: MutableMap<String, Any> = Collections.synchronizedMap(mutableMapOf())
        val commits = AtomicInteger()
        val commitThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

        @Volatile var failNextCommit = false

        @Volatile var beforeMemoryCommit: (() -> Unit)? = null

        @Volatile var afterMemoryCommit: (() -> Unit)? = null
        val vault = ControlledVault()
        private val preferences = mockk<SharedPreferences>()
        private val context = mockk<Context>()
        private val verifier = mockk<PrivacyProviderVerifier>()

        init {
            every { context.applicationContext } returns context
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
                    commits.incrementAndGet()
                    commitThreads += Thread.currentThread()
                    beforeMemoryCommit?.invoke()
                    values.putAll(pending)
                    afterMemoryCommit?.invoke()
                    (!failNextCommit).also { saved ->
                        failNextCommit = false
                        if (saved) synchronized(values) { persistedValues.putAll(values) }
                    }
                }
                editor
            }
        }

        val store = recreate()
        fun recreate() = PrivacyModeStore(context, verifier, vault)
        fun initializeActivePrivacy() = PrivacyModeStore.initialize(context)
        fun awaitUpdates() = runBlocking { withTimeout(5_000) { store.updates.first { !it.saving } } }
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
