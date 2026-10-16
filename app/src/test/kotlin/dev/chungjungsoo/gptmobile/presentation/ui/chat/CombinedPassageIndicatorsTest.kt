package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CombinedPassageIndicatorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun identicalWordingShowsBothProfileBadgesWithoutDebugAndUnknownWordingRemovesThem() {
        val shared = "The government created a conscript army and built new railways."
        val sources = listOf(CombinedModelResponse("one", "One", content = shared), CombinedModelResponse("two", "Two", content = shared))
        val identities = ProfileColorRegistry.ensure(emptyMap(), listOf("one", "two"))
        var passage by mutableStateOf(shared)
        var selected: String? = null
        compose.setContent {
            MaterialTheme { CombinedPassageIndicators(passage, sources, identities) { selected = it } }
        }
        val first = "[${identities.getValue("one").marker}] One"
        val second = "[${identities.getValue("two").marker}] Two"
        compose.waitUntil(5000) { compose.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(first).assertIsDisplayed().performClick()
        assertEquals("one", selected)
        compose.onNodeWithText(second).assertIsDisplayed().performClick()
        assertEquals("two", selected)
        compose.runOnIdle { passage = "A paraphrased statement whose support has not been verified." }
        compose.waitForIdle()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
    }
}
