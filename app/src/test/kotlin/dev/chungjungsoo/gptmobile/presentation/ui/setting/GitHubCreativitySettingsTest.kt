package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.chungjungsoo.gptmobile.data.github.GitHubCreativityMode
import dev.chungjungsoo.gptmobile.data.github.GitHubSamplingSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubCreativitySettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun adaptiveIsDefaultAndManualKeepsTheSavedSliderValue() {
        var settings by mutableStateOf(GitHubSamplingSettings())
        compose.setContent { MaterialTheme { GitHubCreativitySettings(settings, true) { settings = it } } }
        compose.onNodeWithText("Design creativity").assertIsDisplayed()
        compose.onNodeWithText("80%").assertIsDisplayed()
        compose.onNodeWithTag("github_design_creativity").performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
        compose.onNodeWithTag("github_creativity_manual").performClick()
        compose.runOnIdle {
            assertEquals(GitHubCreativityMode.MANUAL, settings.mode)
            assertEquals(.4f, settings.creativity, .001f)
        }
        compose.onNodeWithTag("github_creativity_adaptive").performClick()
        compose.onNodeWithText("Design creativity").assertIsDisplayed()
        compose.onNodeWithText("40%").assertIsDisplayed()
    }
}
