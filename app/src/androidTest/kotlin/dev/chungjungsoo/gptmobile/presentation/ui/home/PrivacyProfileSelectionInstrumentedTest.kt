package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrivacyProfileSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun privacyOffAndEveryVerificationStatusAllowSelectionAndDeselection() {
        val profile = PlatformV2(uid = "privacy-navigation", name = "Saved AI profile", model = "fixture-model", enabled = true)
        var status by mutableStateOf<PrivacyProfileEligibility.Status?>(null)
        var selected by mutableStateOf(false)
        val clicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    SelectPlatformProfileCard(
                        profile = profile,
                        selected = selected,
                        insight = null,
                        onToggle = { uid ->
                            clicks += uid
                            selected = !selected
                        },
                        onFavorite = {},
                        onInsights = {},
                        privacyStatus = status
                    )
                }
            }
        }

        val statuses = listOf(null) + PrivacyProfileEligibility.Status.entries
        statuses.forEach { next ->
            compose.runOnIdle { status = next }
            compose.onNodeWithTag("profile_select_${profile.uid}")
                .assertIsEnabled().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("profile_select_${profile.uid}")
                .assertIsEnabled().performClick().assertIsOff()
        }
        assertEquals(List(statuses.size * 2) { profile.uid }, clicks)
    }
}
