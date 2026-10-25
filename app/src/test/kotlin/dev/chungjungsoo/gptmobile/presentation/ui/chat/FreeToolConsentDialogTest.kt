package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataGrantOptions
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class FreeToolConsentDialogTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val profile = PlatformV2(uid = "profile", name = "Selected writer", compatibleType = ClientType.FREE, apiUrl = FreeAiProvider.OVHCLOUD.apiUrl, model = "model")

    @Test fun healthConsentRequiresAnExplicitChoiceAndCarriesTheSelectedRangeAndLifetime() {
        var accepted: PersonalDataGrantOptions? = null
        val request = ChatViewModel.FreeToolConsentRequest(profile.uid, profile.name, "health_connect_sleep", "Sleep", profile, 7)
        compose.setContent { MaterialTheme { FreeToolConsentDialog(request, { accepted = it }, {}) } }
        compose.onNodeWithText("Share personal data with this provider?").assertIsDisplayed()
        assertNull(accepted)
        compose.onNodeWithText("Last 30 calendar days").performScrollTo().performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performScrollTo().performClick()
        compose.onNodeWithText("Allow for this profile").performClick()
        assertEquals(30, accepted?.healthRangeDays)
        assertTrue(requireNotNull(accepted).rememberForProfile)
    }

    @Test fun locationConsentDefaultsToCoarseAndPreciseSharingNeedsItsOwnSelection() {
        var accepted: PersonalDataGrantOptions? = null
        val request = ChatViewModel.FreeToolConsentRequest(profile.uid, profile.name, "device_location", "Device location", profile, 7)
        compose.setContent { MaterialTheme { FreeToolConsentDialog(request, { accepted = it }, {}) } }
        compose.onNodeWithText("Allow for this chat").performClick()
        assertFalse(requireNotNull(accepted).preciseLocation)
        assertFalse(requireNotNull(accepted).rememberForProfile)
        compose.onNodeWithText("Precise coordinates and altitude").performScrollTo().performClick()
        compose.onNodeWithText("Allow for this chat").performClick()
        assertTrue(requireNotNull(accepted).preciseLocation)
    }
}
