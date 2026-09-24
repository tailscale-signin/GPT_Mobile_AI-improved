package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.net.Uri
import dev.chungjungsoo.gptmobile.data.backup.BackupRestoreResult
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupManager
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompleteBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val manager = mockk<CompleteBackupManager>()
    private lateinit var viewModel: SettingViewModelV2

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val settings = mockk<SettingRepository>(relaxed = true)
        every { settings.observePlatformV2s() } returns flowOf(emptyList())
        every { settings.observeDebugMode() } returns flowOf(false)
        coEvery { settings.getLocalRuntimeBackend() } returns LocalRuntimeBackend.DEFAULT
        every { manager.getBackupStatus() } returns BackupStatus()
        viewModel = SettingViewModelV2(settings, manager)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun pickerCancellationUnlocksActionsAndNeverRunsBackup() = runTest(dispatcher) {
        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        assertFalse(viewModel.prepareBackupPicker(restoring = true))
        viewModel.backupDestinationSelected(null)
        assertTrue(viewModel.backupUi.value.canBackup)
        coVerify(exactly = 0) { manager.backup(any()) }
    }

    @Test
    fun passwordlessBackupAndRestoreUseSameManager() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        coEvery { manager.backup(uri) } returns BackupRestoreResult(true, "Saved")
        coEvery { manager.requiresPassword(uri) } returns false
        coEvery { manager.restore(uri, null) } returns BackupRestoreResult(true, "Restored")

        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()

        coVerify(exactly = 1) { manager.backup(uri) }
        assertEquals("Saved", viewModel.backupUi.value.message)

        assertTrue(viewModel.prepareBackupPicker(restoring = true))
        viewModel.restoreSourceSelected(uri)
        advanceUntilIdle()
        assertFalse(viewModel.backupUi.value.requiresLegacyPassword)
        assertEquals(uri, viewModel.backupUi.value.restoreUri)

        viewModel.confirmRestore()
        advanceUntilIdle()

        coVerify(exactly = 1) { manager.restore(uri, null) }
        assertFalse(viewModel.backupUi.value.isBusy)
        assertEquals("Restored", viewModel.backupUi.value.message)
    }

    @Test
    fun encryptedLegacyBackupPromptsForPasswordBeforeRestore() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        coEvery { manager.requiresPassword(uri) } returns true
        coEvery { manager.restore(uri, "old-password") } returns BackupRestoreResult(true, "Restored")

        assertTrue(viewModel.prepareBackupPicker(restoring = true))
        viewModel.restoreSourceSelected(uri)
        advanceUntilIdle()

        assertTrue(viewModel.backupUi.value.requiresLegacyPassword)
        viewModel.confirmRestore()
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.restore(any(), any()) }

        viewModel.updateLegacyBackupPassword("old-password")
        viewModel.confirmRestore()
        advanceUntilIdle()

        coVerify(exactly = 1) { manager.restore(uri, "old-password") }
        assertEquals("Restored", viewModel.backupUi.value.message)
    }
}
