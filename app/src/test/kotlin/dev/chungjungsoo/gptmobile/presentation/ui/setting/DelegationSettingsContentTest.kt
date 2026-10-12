package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class DelegationSettingsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun detailedControlsStayHiddenUntilAdvancedIsExpanded() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelDelegationSettingsContent(ModelDelegationSettings(), emptyList(), false, null, {}, {})
                }
            }
        }
        compose.onNodeWithText("Search Queries: 9").assertDoesNotExist()
        compose.onNodeWithText("Workers").performScrollTo().performClick()
        compose.onNodeWithTag("delegate_model_dropdown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Overview").performScrollTo().performClick()
        compose.onNodeWithTag("delegation_advanced").performScrollTo().performClick()
        compose.onNodeWithText("Search Queries: 9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pages To Read: 11").performScrollTo().assertIsDisplayed()
    }
}
