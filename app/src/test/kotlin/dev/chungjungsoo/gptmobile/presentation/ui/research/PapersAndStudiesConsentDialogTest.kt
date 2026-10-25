package dev.chungjungsoo.gptmobile.presentation.ui.research

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class PapersAndStudiesConsentDialogTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun offersAcceptAndDeclineWithoutAToggleOrImplicitAcceptance() {
        var accepted = 0
        var declined = 0
        compose.setContent { MaterialTheme { PapersAndStudiesConsentDialog("Selected Examiner", { accepted++ }, { declined++ }) } }
        compose.onNodeWithText("Enable Papers and studies consent for this profile").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(0)
        assertEquals(0, accepted)
        compose.onNodeWithText("Decline").performClick()
        assertEquals(0, accepted)
        assertEquals(1, declined)
        compose.onNodeWithText("Accept").performClick()
        assertEquals(1, accepted)
    }
}
