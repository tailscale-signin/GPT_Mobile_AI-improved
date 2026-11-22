package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResearchEngineHealthPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceSwitchAndCitationShortcutSurviveUnavailableHistory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val noHistory = object : ContextWrapper(app) {
            override fun getFilesDir(): File = error("History unavailable")
        }
        var disabled by mutableStateOf(setOf("crossref"))
        var editable by mutableStateOf(true)
        var imports = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides noHistory) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ResearchEngineHealthPanel(
                            disabledEngineIds = disabled,
                            onEngineEnabled = { id, selected -> disabled = if (selected) disabled - id else disabled + id },
                            editingEnabled = editable,
                            onOpenCitationImport = { imports++ }
                        )
                    }
                }
            }
        }
        val source = compose.onNodeWithText("Crossref")
        source.assertIsOff().performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { editable = false }
        source.assertIsNotEnabled()
        compose.onNodeWithText("Unavailable integrations (not queued)").performScrollTo().performClick()
        compose.onNodeWithText("Import citations or open Google Scholar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
    }
}
