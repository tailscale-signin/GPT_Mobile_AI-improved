package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CombinedProfileBubblesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `source selection fades the composer without moving the viewport and combined restores it within half a second`() {
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CombinedChatComposer(visible, Modifier.testTag("composer-wrapper")) {
                    Box(Modifier.width(300.dp).height(80.dp).testTag("input-bar"))
                }
            }
        }
        compose.onNodeWithTag("input-bar").assertIsDisplayed()

        compose.runOnIdle { visible = false }
        // Commit recomposition and Android's separate layout pass before timing the transition.
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("input-bar").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("input-bar").assertDoesNotExist()
        compose.onNodeWithTag("composer-wrapper").assertHeightIsEqualTo(80.dp)

        compose.runOnIdle { visible = true }
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("input-bar").assertIsDisplayed()
        compose.onNodeWithTag("composer-wrapper").assertHeightIsEqualTo(80.dp)
    }

    @Test
    fun `profile buttons stay selected and inspectable when generation completes or fails`() {
        var selectedUid by mutableStateOf<String?>(null)
        var profiles by mutableStateOf(listOf(profile("lead", CombinedResponseStatus.GENERATING), profile("other", CombinedResponseStatus.GENERATING)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(600.dp)) {
                    CombinedProfileBubbles(
                        profiles = profiles,
                        selectedUid = selectedUid,
                        combinedStatus = CombinedResponseStatus.GENERATING,
                        animateArrival = true,
                        onSelectProfile = { selectedUid = it }
                    )
                    Text(selectedUid ?: "Combined", Modifier.testTag("selected-response"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(1700)
        compose.onNodeWithTag("combined-profile-lead").performClick()
        compose.runOnIdle {
            assertEquals("lead", selectedUid)
            profiles = listOf(profile("lead", CombinedResponseStatus.COMPLETED), profile("other", CombinedResponseStatus.FAILED))
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("combined-profile-lead")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completed"))
        compose.onNodeWithTag("combined-profile-other")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed"))
            .performClick()
        compose.runOnIdle { assertEquals("other", selectedUid) }
        compose.onNodeWithTag("combined-response-tab").performClick()
        compose.runOnIdle { assertEquals(null, selectedUid) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("combined-response-tab").assertIsSelected()
        compose.onNodeWithTag("combined-profile-lead").assertIsDisplayed()
    }

    private fun profile(uid: String, status: CombinedResponseStatus) = CombinedResponseProfile(
        uid = uid,
        name = uid,
        assistantIndex = 0,
        message = MessageV2(content = "Answer from $uid", platformType = uid),
        status = status
    )
}
