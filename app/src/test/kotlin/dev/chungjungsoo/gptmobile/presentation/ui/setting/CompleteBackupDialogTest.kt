package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w320dp-h480dp")
class CompleteBackupDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeTextScrollsToBothActionsAndCallbacksAreWired() {
        val state = mutableStateOf(SettingViewModelV2.BackupUiState())
        var backups = 0
        var restores = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    CompleteBackupDialog(
                        state.value,
                        BackupStatus(),
                        onBackup = { backups++ },
                        onRestore = { restores++ },
                        onDismiss = {}
                    )
                }
            }
        }
        scroll("backup_all")
        compose.onNodeWithTag("backup_all").assertIsDisplayed().performClick()
        scroll("backup_contents")
        compose.onNodeWithTag("backup_contents").assertIsDisplayed().performClick()
        scroll("restore_all")
        compose.onNodeWithTag("restore_all").assertIsDisplayed().performClick()
        assertEquals(1, backups)
        assertEquals(1, restores)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    fun landscapeBusyStateDisablesBothActions() {
        compose.setContent {
            MaterialTheme {
                CompleteBackupDialog(
                    SettingViewModelV2.BackupUiState(isBusy = true, isWorking = true),
                    BackupStatus(),
                    {},
                    {},
                    {}
                )
            }
        }
        scroll("backup_all")
        compose.onNodeWithTag("backup_all").assertIsDisplayed().assertIsNotEnabled()
        scroll("restore_all")
        compose.onNodeWithTag("restore_all").assertIsDisplayed().assertIsNotEnabled()
    }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("backup_content").performScrollToNode(hasTestTag(tag))
    }
}
