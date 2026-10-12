package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import org.junit.Rule
import org.junit.Test

class DeepResearchSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyCanonicalIntegratedCardExpandsWithoutAMissingIdentityLookup() {
        val selected = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                key(selected.value) {
                    val definition = ToolServiceCatalog.integrated[selected.value]
                    ToolServiceCard(ToolServiceItem(definition, true, emptyList(), emptyList()), false, {}, "In-app plugin") {
                        Text("Configure ${pluginSettingsDestination(definition)}")
                    }
                }
            }
        }
        ToolServiceCatalog.integrated.forEachIndexed { index, definition ->
            compose.runOnIdle { selected.value = index }
            compose.onNodeWithContentDescription("Expand ${definition.name}").performClick()
            compose.onNodeWithText("Configure ${pluginSettingsDestination(definition)}").assertIsDisplayed()
        }
    }

    @Test fun disabledOfflineSettingsShowALocalCustomGoalWithEnlargedText() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        DeepResearchSettingsCard(DeepResearchSettings(customSourceTarget = 500).forEffort(100), false, {})
                    }
                }
            }
        }
        compose.onNodeWithText("100% effort · 500-source goal · No additional cap").assertExists()
    }
}
