package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises real Compose layout and dialogs; this is not a TalkBack or Qualcomm qualification. */
class Release0950InstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun planEditorAppliesStepsAtCompactWidthLargeFontAndRtl() {
        var result = ModelDelegationSettings()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    var config by remember { mutableStateOf(result) }
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        DelegationPlanEditor(config, emptyList()) { transform ->
                            config = transform(config)
                            result = config
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Edit steps and dependencies").performScrollTo().performClick()
        compose.onNodeWithText("Add step").performScrollTo().performClick()
        compose.onNodeWithText("Task instruction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply plan").performClick()
        assertEquals(1, result.taskPlan.steps.size)
        compose.onNodeWithText("Edit steps and dependencies").assertIsDisplayed()
        // Force a real frame capture; rendering failures must fail the device test.
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, "acceptance-0950-editor.png").outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
