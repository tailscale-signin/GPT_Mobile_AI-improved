package dev.chungjungsoo.gptmobile.data.privacy

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsWriterTest {
    @Test
    fun rapidMasterAndCategoryChangesPersistInSubmissionOrder() = runTest {
        val saved = mutableListOf<PrivacySettingChange>()
        val writer = PrivacySettingsWriter(backgroundScope, { saved += it }, StandardTestDispatcher(testScheduler))
        val choices = listOf(
            PrivacySettingChange.Enabled(true),
            PrivacySettingChange.Enabled(false),
            PrivacySettingChange.Category(PrivacyCategory.CONTACTS, false),
            PrivacySettingChange.Enabled(true),
            PrivacySettingChange.Category(PrivacyCategory.CONTACTS, true),
            PrivacySettingChange.Enabled(false)
        )

        choices.forEach(writer::submit)
        assertEquals(choices.size, writer.state.value.pending)
        runCurrent()

        assertEquals(choices, saved)
        assertEquals(PrivacySettingsSaveState(), writer.state.value)
    }

    @Test
    fun persistenceRunsOffTheCallerAndAWaitingWriteDoesNotBlockAnotherControl() {
        val caller = Thread.currentThread()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val savingThreads = mutableListOf<Thread>()
        val saved = mutableListOf<PrivacySettingChange>()
        val writer = PrivacySettingsWriter(applicationScope, { change ->
            savingThreads += Thread.currentThread()
            if (saved.isEmpty()) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            saved += change
        }, dispatcher)
        try {
            writer.submit(PrivacySettingChange.Enabled(true))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            writer.submit(PrivacySettingChange.Enabled(false))
            writer.submit(PrivacySettingChange.Category(PrivacyCategory.LOCATIONS, false))
            assertEquals(3, writer.state.value.pending)
            release.countDown()
            runBlocking { withTimeout(5_000) { writer.state.first { !it.saving } } }

            assertEquals(3, saved.size)
            assertTrue(savingThreads.all { it != caller })
            assertNotSame(caller, savingThreads.first())
            assertFalse(writer.state.value.failed)
        } finally {
            release.countDown()
            applicationScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun failureDoesNotStopOtherControlsAndRetryRecoversEveryFailedControl() = runTest {
        val calls = mutableListOf<PrivacySettingChange>()
        val failuresRemaining = mutableSetOf("master", PrivacyCategory.CONTACTS.name)
        val writer = PrivacySettingsWriter(backgroundScope, { change ->
            calls += change
            if (failuresRemaining.remove(change.key)) throw IOException("Simulated persistence failure")
        }, StandardTestDispatcher(testScheduler))
        val master = PrivacySettingChange.Enabled(true)
        val contacts = PrivacySettingChange.Category(PrivacyCategory.CONTACTS, false)
        val locations = PrivacySettingChange.Category(PrivacyCategory.LOCATIONS, false)

        listOf(master, contacts, locations).forEach(writer::submit)
        runCurrent()
        assertEquals(listOf(master, contacts, locations), calls)
        assertEquals(PrivacySettingsSaveState(failed = true), writer.state.value)

        writer.retry()
        assertEquals(2, writer.state.value.pending)
        runCurrent()

        assertEquals(listOf(master, contacts, locations, master, contacts), calls)
        assertEquals(PrivacySettingsSaveState(), writer.state.value)
        writer.retry()
        runCurrent()
        assertEquals(5, calls.size)
    }

    @Test
    fun successfulNewerChoiceSupersedesOnlyThatControlsFailedChoice() = runTest {
        val calls = mutableListOf<PrivacySettingChange>()
        val failuresRemaining = mutableSetOf("master", PrivacyCategory.CONTACTS.name)
        val writer = PrivacySettingsWriter(backgroundScope, { change ->
            calls += change
            if (failuresRemaining.remove(change.key)) throw IOException("Simulated persistence failure")
        }, StandardTestDispatcher(testScheduler))
        val oldMaster = PrivacySettingChange.Enabled(true)
        val contacts = PrivacySettingChange.Category(PrivacyCategory.CONTACTS, false)
        val currentMaster = PrivacySettingChange.Enabled(false)

        listOf(oldMaster, contacts, currentMaster).forEach(writer::submit)
        runCurrent()
        assertTrue(writer.state.value.failed)
        writer.retry()
        runCurrent()

        assertEquals(listOf(oldMaster, contacts, currentMaster, contacts), calls)
        assertFalse(writer.state.value.failed)
    }

    @Test
    fun retryWhileAnotherChoiceIsQueuedDoesNotDuplicatePendingChanges() = runTest {
        val calls = mutableListOf<PrivacySettingChange>()
        var first = true
        val writer = PrivacySettingsWriter(backgroundScope, { change ->
            calls += change
            if (first) {
                first = false
                throw IOException("Simulated persistence failure")
            }
        }, StandardTestDispatcher(testScheduler))
        val old = PrivacySettingChange.Enabled(true)
        val replacement = PrivacySettingChange.Enabled(false)

        writer.submit(old)
        runCurrent()
        writer.submit(replacement)
        writer.retry()
        writer.retry()
        assertEquals(1, writer.state.value.pending)
        runCurrent()

        assertEquals(listOf(old, replacement), calls)
        assertEquals(PrivacySettingsSaveState(), writer.state.value)
    }

    @Test
    fun leavingTheSubmittingScreenDoesNotCancelApplicationOwnedSaves() = runTest {
        val saved = mutableListOf<PrivacySettingChange>()
        val writer = PrivacySettingsWriter(backgroundScope, { saved += it }, StandardTestDispatcher(testScheduler))
        val choice = PrivacySettingChange.Enabled(true)
        val screen = launch(UnconfinedTestDispatcher(testScheduler)) {
            writer.submit(choice)
            awaitCancellation()
        }

        screen.cancelAndJoin()
        runCurrent()

        assertEquals(listOf(choice), saved)
        assertEquals(PrivacySettingsSaveState(), writer.state.value)
    }

    @Test
    fun resultReportingFailureDoesNotStopFollowingChangesOrRetries() = runTest {
        val saved = mutableListOf<PrivacySettingChange>()
        var first = true
        val writer = PrivacySettingsWriter(
            backgroundScope,
            persist = { change ->
                saved += change
                if (first) {
                    first = false
                    throw IOException("Simulated persistence failure")
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            onResult = { _, _ -> throw IllegalStateException("Simulated diagnostics failure") }
        )
        val master = PrivacySettingChange.Enabled(true)
        val category = PrivacySettingChange.Category(PrivacyCategory.CONTACTS, false)
        writer.submit(master)
        writer.submit(category)
        runCurrent()
        assertEquals(PrivacySettingsSaveState(failed = true), writer.state.value)

        writer.retry()
        runCurrent()

        assertEquals(listOf(master, category, master), saved)
        assertEquals(PrivacySettingsSaveState(), writer.state.value)
    }
}
