package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class ChatMarkdownWordingTest {
    // Queue recomposition on the test clock: asynchronous Markdown emissions must
    // not resume Android view updates inline on the parser's worker thread.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun combinedProseReceivesBothProfileFontColoursAndRecomputesWhenSourcesChange() {
        val athens = "Silver mines funded public works and naval power in ancient Athens."
        val tang = "Poetry flourished as Li Bai and Du Fu became revered across China."
        var sources by mutableStateOf(listOf(CombinedModelResponse("one", "One", content = athens), CombinedModelResponse("two", "Two", content = tang)))
        compose.setContent {
            MaterialTheme {
                ChatMarkdown(
                    content = "😀 **Athens**\n\n$athens\n\n**Tang**\n\n$tang",
                    wording = CombinedWordingPresentation(sources, mapOf("one" to Color.Red, "two" to Color.Blue))
                )
            }
        }
        fun colours(): Set<Color> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.flatMap { it.spanStyles }.map { it.item.color }.toSet()
        compose.waitUntil(5000) { colours().containsAll(setOf(Color.Red, Color.Blue)) }
        compose.runOnIdle { sources = emptyList() }
        compose.waitUntil(5000) { colours().none { it == Color.Red || it == Color.Blue } }
    }

    @Test fun restoredMixedHistoryColoursOnlyTheVerifiedGatewayText() {
        val legacyText = "Legacy remote response."
        val gatewayText = "Verified gateway response."
        val directText = "Direct remote response."
        val unknownText = "Unattributed saved response."
        val legacy = ResponseSource(ResponseTransport.GATEWAY, profileUid = "same-profile")
        val saved = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Earlier gateway progress.", responseSource = legacy.copy(gatewayVerified = true)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, legacyText, responseSource = legacy),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, gatewayText, responseSource = legacy.copy(gatewayVerified = true)),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, directText, responseSource = ResponseSource(ResponseTransport.DIRECT, profileUid = "same-profile")),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, unknownText)
        )
        var timeline by mutableStateOf(Json.decodeFromString<List<AssistantTimelineItem>>(Json.encodeToString(saved)))
        var accent by mutableStateOf(Color(0xff6750a4))
        val surface = Color.White
        val ordinary = Color(0xff1b1b1b)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = accent)) {
                AssistantChronologicalContent(
                    timeline = timeline,
                    toolEvents = emptyList(),
                    fallbackText = timeline.filter { it.type == AssistantTimelineItemType.TEXT }.joinToString("") { it.content },
                    fallbackThoughts = "",
                    contentIdentity = "restored-source-proof",
                    isLoading = false,
                    animateResponse = false,
                    proseColor = ordinary,
                    responseSurface = surface,
                    debugMode = false,
                    showReasoning = false,
                    expanded = false,
                    onExpandedChange = {}
                )
            }
        }
        fun colours(text: String): Set<Color> = compose.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.flatMap { it.spanStyles }.map { it.item.color }.filter { it != Color.Unspecified }.toSet()
        fun ordinaryTextRetainsItsColour(): Boolean = listOf(legacyText, directText, unknownText).all { colours(it) == setOf(ordinary) }
        compose.waitUntil(5000) {
            ordinaryTextRetainsItsColour() && colours(gatewayText) == setOf(ResponseSourceAppearance.gatewayForeground(accent, surface))
        }
        compose.onNodeWithText("Gateway").assertExists()
        compose.runOnIdle { accent = Color(0xff006a60) }
        compose.waitUntil(5000) {
            ordinaryTextRetainsItsColour() && colours(gatewayText) == setOf(ResponseSourceAppearance.gatewayForeground(accent, surface))
        }
        compose.runOnIdle { timeline = timeline.filterNot { it.content == gatewayText } }
        compose.waitUntil(5000) { ordinaryTextRetainsItsColour() }
        compose.onNodeWithText("Gateway").assertDoesNotExist()
    }
}
