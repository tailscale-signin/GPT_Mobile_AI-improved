package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The acceptance script constrains the actual window to 320 dp and system font scale to 200%. */
class PlatformPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactLightSelectionAndAccessibleFavorite() = verify(false, false)

    @Test fun compactDarkRtlSelectionAndAccessibleFavorite() = verify(true, true)

    private fun verify(dark: Boolean, rtl: Boolean) {
        val profiles = (0..39).map { PlatformV2(id = it + 1, uid = "fixture-$it", name = "Profile $it with a long accessible name", model = "synthetic/model-$it", enabled = true) }
        var selected by mutableStateOf(setOf("fixture-0", "fixture-1"))
        var favorites = 0
        var confirmations = 0
        var mode = ""
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    SelectPlatformDialog(profiles, profiles.map { it.uid in selected }, {}, { values, selectedMode ->
                        assertEquals(listOf("fixture-0"), values)
                        mode = selectedMode
                        confirmations++
                    }, { uid -> selected = if (uid in selected) selected - uid else selected + uid }, { favorites++ }, initialCombined = true)
                }
            }
        }
        compose.onNodeWithTag("picker_confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("picker_list").performScrollToNode(hasTestTag("profile_select_fixture-1"))
        compose.onNodeWithTag("profile_select_fixture-1").performSemanticsAction(SemanticsActions.CustomActions) { actions -> check(actions.single().action()) }
        assertEquals(1, favorites)
        compose.onNodeWithTag("profile_select_fixture-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("picker_confirm").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.filesDir, "picker-${if (dark) "dark-rtl" else "light"}-200-percent.png").outputStream().use {
            check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithTag("picker_confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, confirmations)
        assertEquals(ConversationMode.STANDARD, mode)
    }
}
