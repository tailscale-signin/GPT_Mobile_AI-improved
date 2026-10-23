package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeModelOption
import dev.chungjungsoo.gptmobile.data.free.FreeServiceStatus
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AuditActionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelPickerKeepsTheChosenProfileAfterReordering() {
        val provider = FreeAiProvider.VIREONIX
        var profiles by mutableStateOf(listOf(provider.applyTo(PlatformV2(uid = "a", name = "Profile A")), provider.applyTo(PlatformV2(uid = "b", name = "Profile B"))))
        val original = profiles.first().model
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FreeServiceCard(provider, FreeServiceStatus(), profiles, listOf(FreeModelOption("new-model")), {}, { updated -> profiles = profiles.map { if (it.uid == updated.uid) updated else it } })
                }
            }
        }
        compose.onAllNodesWithText("Choose model")[1].performScrollTo().performClick()
        compose.onNodeWithText("Model for Profile B").performScrollTo()
        compose.runOnIdle { profiles = profiles.reversed() }
        compose.onNodeWithText("Use this model").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(original, profiles.single { it.uid == "a" }.model)
            assertEquals("new-model", profiles.single { it.uid == "b" }.model)
        }
    }
}
