package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageLink
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedSourceReference
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1000dp")
@OptIn(ExperimentalTestApi::class)
class CombinedOriginalSourceDialogTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val shared = "The government created a conscript army and built new railways."
    private val label = "Shared contribution from One and Two. Inspect original replies."

    @Test fun normalMarkdownShowsAnAccessibleJointMarkerAndBothOriginalProfiles() {
        val sources = listOf(CombinedModelResponse("one", "One", content = shared), CombinedModelResponse("two", "Two", content = shared))
        compose.setContent {
            MaterialTheme { ChatMarkdown(content = shared, wording = CombinedWordingPresentation(sources, mapOf("one" to Color.Red, "two" to Color.Blue), profileMarkers = mapOf("one" to "A", "two" to "B"))) }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Shared · [A] One · [B] Two").assertIsDisplayed()
        compose.onNodeWithContentDescription(label).performClick()
        compose.onNodeWithText("Shared contribution").assertIsDisplayed()
        compose.onNodeWithText("One", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Two", substring = false).assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Original reply · Two").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Original reply · Two").assertIsDisplayed()
    }

    @Test fun durableAnswerOpensOnlyRequestedOriginalPagesAndKeepsOtherContributorsAvailable() {
        val owner = "combined-synthesis:test"
        val sources = listOf(
            CombinedModelResponse("one", "One", content = "", originalDocument = CombinedSourceReference(owner, "source-one", 2, 100, "one")),
            CombinedModelResponse("two", "Two", content = "", originalDocument = CombinedSourceReference(owner, "source-two", 1, 50, "two"))
        )
        val requested = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val passage = CombinedPassageRecord(0, shared.length, researchHash(shared), setOf("one", "two"), origin = "DURABLE_BLOCK_MAPPING_V1", linkVersion = 1, links = listOf(CombinedPassageLink(0, shared.length, researchHash(shared), setOf("one", "two"), emptySet())))
        val reference = CombinedManifestReference("manifest", owner, "revision", shared.length, 1, researchHash(shared))
        val wording = CombinedWordingPresentation(
            sources,
            mapOf("one" to Color.Red, "two" to Color.Blue),
            loadSourceBlocks = { source, first, count ->
                assertEquals(1, count)
                requested += source.platformUid to first
                val text = if (source.platformUid == "two") {
                    "Second original profile retains its own complete wording."
                } else if (first == 0) {
                    "First original page is retained."
                } else {
                    "Late qualification on the next original page is retained."
                }
                listOf(CombinedDocumentBlock(first, first * 50, text, researchHash(text), emptyList()))
            }
        )
        compose.setContent {
            MaterialTheme { BoundedAssistantDocument("saved", reference, { _, _, _ -> listOf(CombinedDocumentBlock(0, 0, shared, researchHash(shared), listOf(passage))) }, wording, null) }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requested.isEmpty())
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("First original page is retained.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("one" to 0), requested.toList())
        compose.onNodeWithText("Next page").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Late qualification on the next original page is retained.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Original reply page 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("Two", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Second original profile retains its own complete wording.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("one" to 0, "one" to 1, "two" to 0), requested.toList())
    }
}
