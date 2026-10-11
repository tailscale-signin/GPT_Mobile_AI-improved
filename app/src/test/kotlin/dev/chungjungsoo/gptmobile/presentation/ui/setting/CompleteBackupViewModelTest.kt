package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.net.Uri
import dev.chungjungsoo.gptmobile.data.backup.BackupProtection
import dev.chungjungsoo.gptmobile.data.backup.BackupRestoreResult
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupManager
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
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
    private val manager = mockk<CompleteBackupManager>(relaxed = true)
    private lateinit var viewModel: SettingViewModelV2

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val settings = mockk<SettingRepository>(relaxed = true)
        every { settings.observePlatformV2s() } returns flowOf(emptyList())
        every { settings.observeDebugMode() } returns flowOf(false)
        every { settings.observeProviderConnections() } returns flowOf(emptyList())
        every { settings.observeLocalRuntimeBackend() } returns flowOf(LocalRuntimeBackend.DEFAULT)
        every { settings.observeFeatureSettings() } returns flowOf(dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
        coEvery { settings.getLocalRuntimeBackend() } returns LocalRuntimeBackend.DEFAULT
        every { manager.getBackupStatus() } returns BackupStatus()
        coEvery { manager.savedProtection() } returns BackupProtection()
        every { manager.savedSelection() } returns CompleteBackupSelection.ALL
        viewModel = SettingViewModelV2(settings, manager, dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime())
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
    fun unencryptedBackupDoesNotRequireRecoveryKeyAndRestoreUsesSameManager() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        // ALL includes sensitive sections and must never take the plaintext path.
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS))
        viewModel.clearBackupSections()
        viewModel.updateBackupSection(CompleteBackupSection.SETTINGS, true)
        assertEquals(selection, viewModel.backupUi.value.selection)
        assertFalse(selection.requiresEncryption)
        coEvery { manager.backup(uri, selection, null, encrypt = false) } returns BackupRestoreResult(true, "Saved")
        coEvery { manager.requiresPassword(uri) } returns false
        coEvery { manager.restore(uri, null) } returns BackupRestoreResult(true, "Restored")

        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()
        assertEquals(null, viewModel.backupUi.value.backupUri)
        coVerify(exactly = 1) { manager.backup(uri, selection, null, encrypt = false) }
        assertEquals("Saved", viewModel.backupUi.value.message)

        viewModel.selectAllBackupSections()
        assertTrue(viewModel.prepareBackupPicker(restoring = true))
        viewModel.restoreSourceSelected(uri)
        advanceUntilIdle()
        assertEquals(uri, viewModel.backupUi.value.restoreUri)
        assertFalse(viewModel.backupUi.value.requiresLegacyPassword)

        viewModel.confirmRestore()
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.restore(uri, null) }
        assertFalse(viewModel.backupUi.value.isBusy)
        assertEquals("Restored", viewModel.backupUi.value.message)
    }

    @Test
    fun healthBackupWithoutPasswordWaitsForRecoveryKeyAndUsesEncryption() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        val recoveryKey = mockk<Uri>()
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.HEALTH_DATA))
        viewModel.clearBackupSections()
        viewModel.updateBackupSection(CompleteBackupSection.HEALTH_DATA, true)
        assertEquals(selection, viewModel.backupUi.value.selection)
        assertTrue(selection.requiresEncryption)
        coEvery { manager.backup(uri, selection, recoveryKeyUri = recoveryKey, encrypt = true) } returns BackupRestoreResult(true, "Health saved")

        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()
        assertEquals(uri, viewModel.backupUi.value.backupUri)
        assertFalse(viewModel.backupUi.value.isBusy)
        coVerify(exactly = 0) { manager.backup(any(), any(), any(), any(), any()) }

        viewModel.backupRecoveryKeySelected(recoveryKey)
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.backup(uri, selection, recoveryKeyUri = recoveryKey, encrypt = true) }
        assertEquals(null, viewModel.backupUi.value.backupUri)
        assertFalse(viewModel.backupUi.value.isBusy)
        assertEquals("Health saved", viewModel.backupUi.value.message)
    }

    @Test
    fun cancellingHealthRecoveryKeyNeverWritesBackupAndUnlocksActions() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        viewModel.clearBackupSections()
        viewModel.updateBackupSection(CompleteBackupSection.HEALTH_DATA, true)
        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()
        assertEquals(uri, viewModel.backupUi.value.backupUri)

        viewModel.backupRecoveryKeySelected(null)
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.backup(any(), any(), any(), any(), any()) }
        assertEquals(null, viewModel.backupUi.value.backupUri)
        assertTrue(viewModel.backupUi.value.canBackup)
        assertFalse(viewModel.backupUi.value.isWorking)
    }

    @Test
    fun healthBackupWithPasswordDoesNotRequireRecoveryKey() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        val selection = CompleteBackupSelection(setOf(CompleteBackupSection.HEALTH_DATA))
        viewModel.clearBackupSections()
        viewModel.updateBackupSection(CompleteBackupSection.HEALTH_DATA, true)
        viewModel.updateBackupPasswordProtection(true)
        viewModel.updateBackupPassword("health-password")
        advanceUntilIdle()
        coEvery { manager.backup(uri, selection, "health-password", encrypt = true) } returns BackupRestoreResult(true, "Health saved")

        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.backup(uri, selection, "health-password", encrypt = true) }
        assertEquals(null, viewModel.backupUi.value.backupUri)
        assertFalse(viewModel.backupUi.value.isBusy)
        assertEquals("Health saved", viewModel.backupUi.value.message)
    }

    @Test
    fun passwordSurvivesToggleAndDialogReopeningAndPrepopulatesRestore() = runTest(dispatcher) {
        coEvery { manager.savedProtection() } returns BackupProtection(true, "saved-password")
        viewModel.openBackupRestoreDialog()
        advanceUntilIdle()
        assertEquals("saved-password", viewModel.backupUi.value.backupPassword)
        viewModel.updateBackupPasswordProtection(false)
        advanceUntilIdle()
        assertEquals("saved-password", viewModel.backupUi.value.backupPassword)
        coVerify { manager.saveProtection(BackupProtection(false, "saved-password")) }
        viewModel.closeBackupRestoreDialog()
        assertEquals("saved-password", viewModel.backupUi.value.backupPassword)
        viewModel.openBackupRestoreDialog()
        advanceUntilIdle()
        val uri = mockk<Uri>()
        coEvery { manager.requiresPassword(uri) } returns true
        viewModel.restoreSourceSelected(uri)
        advanceUntilIdle()
        assertEquals("saved-password", viewModel.backupUi.value.legacyPassword)
    }

    @Test
    fun encryptedBackupUsesSavedPasswordAndInvalidPasswordCannotOpenPicker() = runTest(dispatcher) {
        viewModel.updateBackupPasswordProtection(true)
        assertFalse(viewModel.prepareBackupPicker(restoring = false))
        viewModel.updateBackupPassword("new-password")
        advanceUntilIdle()
        val uri = mockk<Uri>()
        coEvery { manager.backup(uri, CompleteBackupSelection.ALL, "new-password", encrypt = true) } returns BackupRestoreResult(true, "Saved")
        assertTrue(viewModel.prepareBackupPicker(restoring = false))
        viewModel.backupDestinationSelected(uri)
        advanceUntilIdle()
        coVerify { manager.saveProtection(BackupProtection(true, "new-password")) }
        coVerify { manager.backup(uri, CompleteBackupSelection.ALL, "new-password", encrypt = true) }
        assertFalse(viewModel.backupUi.value.isBusy)
    }

    @Test
    fun legacyEncryptedRestorePromptsOnlyAfterFileInspection() = runTest(dispatcher) {
        viewModel.selectAllBackupSections()
        val uri = mockk<Uri>()
        coEvery { manager.requiresPassword(uri) } returns true
        coEvery { manager.restore(uri, "legacy-pass") } returns BackupRestoreResult(true, "Legacy restored")

        assertTrue(viewModel.prepareBackupPicker(restoring = true))
        viewModel.restoreSourceSelected(uri)
        advanceUntilIdle()

        assertTrue(viewModel.backupUi.value.requiresLegacyPassword)
        viewModel.confirmRestore()
        assertTrue(viewModel.backupUi.value.isError)

        viewModel.updateLegacyBackupPassword("legacy-pass")
        viewModel.confirmRestore()
        advanceUntilIdle()

        coVerify(exactly = 1) { manager.restore(uri, "legacy-pass") }
        assertEquals("Legacy restored", viewModel.backupUi.value.message)
    }
}
