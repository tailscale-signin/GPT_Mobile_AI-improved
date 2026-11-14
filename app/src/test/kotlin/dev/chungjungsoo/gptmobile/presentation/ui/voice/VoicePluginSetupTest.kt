package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceInstallState
import dev.chungjungsoo.gptmobile.data.voice.VoiceTransferStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoicePluginSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queuedNetworkAndLostWorkShowTheirActualStateAndRequireAnExplicitRetry() {
        var installation by mutableStateOf(VoiceInstallState("WAITING_NETWORK", pendingReason = "CONNECTIVITY"))
        val missing = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED)
        var starts = 0
        var reports = 0
        compose.setContent { MaterialTheme { Column { VoicePluginSetup(missing, missing, installation, { starts++ }, {}, { reports++ }) } } }
        compose.onNodeWithText("Waiting for an internet connection…").assertIsDisplayed()
        compose.onNodeWithText("Report download problem").assertIsDisplayed().performClick()
        assertEquals(1, reports)
        assertEquals(0, starts)
        compose.runOnIdle { installation = VoiceInstallState("FAILED", "WORK_NOT_FOUND", pendingReason = "MISSING") }
        compose.onNodeWithText("Android no longer has this download. Start it again to resume verified files.").assertIsDisplayed()
        compose.onNodeWithText("Report download problem").assertIsDisplayed()
        assertEquals(0, starts)
        compose.onNodeWithText("Resume download").performClick()
        assertEquals(1, starts)
    }

    @Test fun connectionAndArchivePreparationAreVisibleBeforeReady() {
        var stage by mutableStateOf(VoiceTransferStage.CONNECTING)
        compose.setContent { MaterialTheme { Column { VoiceTransferProgress(stage, 0, 132_353_410) } } }
        compose.onNodeWithText("Connecting to the voice download…").assertIsDisplayed()
        compose.runOnIdle { stage = VoiceTransferStage.EXTRACTING }
        compose.onNodeWithText("Preparing the downloaded voice pack…").assertIsDisplayed()
        compose.runOnIdle { stage = VoiceTransferStage.VERIFYING }
        compose.onNodeWithText("Verifying voice files…").assertIsDisplayed()
    }
}
