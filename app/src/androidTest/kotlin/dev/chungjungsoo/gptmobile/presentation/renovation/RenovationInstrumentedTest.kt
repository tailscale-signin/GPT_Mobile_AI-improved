package dev.chungjungsoo.gptmobile.presentation.renovation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppEditorScaffold
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ToolApprovalDialog
import dev.chungjungsoo.gptmobile.presentation.ui.home.SelectPlatformDialog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RenovationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun profileSelectionNeverActivatesADisabledProfile() {
        var chosen = emptyList<String>()
        var mode = ""
        val profiles = listOf(profile("enabled", true), profile("disabled", false))
        compose.setContent {
            GPTMobileTheme(themeFamily = "Forest", updateSystemBars = false) {
                SelectPlatformDialog(profiles, listOf(true, true), {}, { ids, value ->
                    chosen = ids
                    mode = value
                }, {})
            }
        }
        compose.onNodeWithText("Start · 1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("enabled"), chosen)
            assertEquals(ConversationMode.STANDARD, mode)
        }
    }

    @Test
    fun combinedSelectionPassesEverySelectedIdentity() {
        var chosen = emptyList<String>()
        var mode = ""
        compose.setContent {
            GPTMobileTheme(themeFamily = "Ocean", updateSystemBars = false) {
                SelectPlatformDialog(listOf(profile("first", true), profile("second", true)), listOf(true, true), {}, { ids, value ->
                    chosen = ids
                    mode = value
                }, {})
            }
        }
        compose.onNodeWithText("Combined reply").performClick()
        compose.onNodeWithText("Start · 2").performClick()
        compose.runOnIdle {
            assertEquals(listOf("first", "second"), chosen)
            assertEquals(ConversationMode.COMBINED, mode)
        }
    }

    @Test
    fun permissionDefaultsToOnceAndDoesNotInvokeBroaderGrants() {
        var once = 0
        var broad = 0
        compose.setContent {
            GPTMobileTheme(themeFamily = "Forest", updateSystemBars = false) {
                ToolApprovalDialog(ToolApproval("approval", "run", "Example provider", "read_file", "hash", "README.md"), {}, { once++ }, { broad++ }, { broad++ })
            }
        }
        compose.onNodeWithText("Allow once").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, once)
            assertEquals(0, broad)
        }
    }

    @Test
    fun editorKeepsDraftUntilExplicitSaveAtLargeTextRtl() {
        var saved = "Original"
        var cancelled = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                GPTMobileTheme(themeFamily = "Iris", updateSystemBars = false) {
                    var draft by rememberSaveable { mutableStateOf("Original") }
                    AppEditorScaffold("Edit profile", draft != "Original", { cancelled++ }, { saved = draft }) { padding ->
                        OutlinedTextField(draft, { draft = it }, modifier = Modifier.padding(padding), label = { Text("Profile name") })
                    }
                }
            }
        }
        compose.onNodeWithText("Original").performTextReplacement("Revised")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle {
            assertEquals("Original", saved)
            assertEquals(0, cancelled)
        }
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Revised", saved) }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-review").apply { mkdirs() }
        File(directory, "renovation-editor-large-rtl.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun providerResetActionRemainsAvailableInRenovatedEditor() {
        var reset = 0
        compose.setContent {
            GPTMobileTheme(themeFamily = "Forest", updateSystemBars = false) {
                dev.chungjungsoo.gptmobile.presentation.designsystem.AppFormDialog(
                    onDismissRequest = {},
                    title = { Text("Provider options") },
                    text = { Text("Current options") },
                    confirmButton = { TextButton(onClick = {}) { Text("Save") } },
                    secondaryButton = { TextButton(onClick = { reset++ }) { Text("Reset") } }
                )
            }
        }
        compose.onNodeWithText("Reset").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, reset) }
    }

    private fun profile(uid: String, enabled: Boolean) = PlatformV2(uid = uid, name = uid, compatibleType = ClientType.OPENAI, apiUrl = "https://example.invalid/v1", model = "fixture", enabled = enabled)
}
