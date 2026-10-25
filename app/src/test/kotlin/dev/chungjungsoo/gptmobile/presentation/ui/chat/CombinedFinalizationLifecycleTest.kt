package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class CombinedFinalizationLifecycleTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun longPressFinalizationScrollingProfileSwitchAndDisposalKeepLayoutOwnershipValid() {
        val paragraph = "A saved paragraph remains readable when its final document replaces the streaming renderer."
        val body = (paragraph + "\n\n").repeat(60)
        val digest = researchHash(body)
        var finalDocument by mutableStateOf(false)
        var revision by mutableIntStateOf(0)
        var profile by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("answer")) {
                    if (visible) {
                        Box(Modifier.verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                AssistantChronologicalContent(
                                    timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, body), AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = CombinedQualityReport(answerDigest = digest, revisionId = "revision-$revision"))),
                                    toolEvents = emptyList(), fallbackText = body, fallbackThoughts = "", contentIdentity = "message-3:profile-$profile", isLoading = !finalDocument,
                                    animateResponse = false, debugMode = false, showExecutionTrace = false, showReasoning = false, expanded = false, onExpandedChange = {},
                                    combinedManifest = if (finalDocument) CombinedManifestReference("manifest-$revision", "run", "revision-$revision", body.length, 1, digest) else null,
                                    loadCombinedBlocks = { _, _, _ -> listOf(CombinedDocumentBlock(0, 0, body, digest, emptyList())) }
                                )
                            }
                        }
                    }
                }
            }
        }
        repeat(6) {
            compose.waitUntil(5000) { compose.onAllNodesWithText(paragraph, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("answer").performTouchInput {
                down(center)
                advanceEventTime(700)
            }
            compose.runOnIdle {
                finalDocument = !finalDocument
                revision++
            }
            compose.onNodeWithTag("answer").performTouchInput { up() }
            compose.onNodeWithTag("answer").performTouchInput { swipeUp() }
            compose.runOnIdle { profile = 1 - profile }
            compose.waitForIdle()
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnIdle { visible = true }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(paragraph, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
