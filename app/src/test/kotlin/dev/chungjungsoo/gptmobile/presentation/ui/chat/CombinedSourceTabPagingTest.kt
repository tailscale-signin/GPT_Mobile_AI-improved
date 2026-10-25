package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedSourceReference
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1200dp")
@OptIn(ExperimentalTestApi::class)
class CombinedSourceTabPagingTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val runId = "combined-synthesis:source-tab"
    private val manifest = CombinedManifestReference("manifest", runId, "revision", 1_000_000, 123, researchHash("final"))

    private fun pages(name: String, count: Int = 12): List<CombinedDocumentBlock> {
        var start = 0
        return List(count) { index ->
            val text = "# $name page $index\n\n" + "The original contributor retains its exact explanation and qualifications.\n\n".repeat(70)
            CombinedDocumentBlock(index, start, text, researchHash(text), emptyList()).also { start += text.length }
        }
    }

    private fun source(uid: String, name: String, pages: List<CombinedDocumentBlock>) = CombinedModelResponse(
        uid,
        name,
        content = "",
        attemptId = "attempt-$uid",
        sourceDigest = researchHash(pages.joinToString("") { it.text }),
        originalDocument = CombinedSourceReference(runId, "source-$uid", pages.size, pages.sumOf { it.text.length }, researchHash(pages.joinToString("") { it.text }))
    )

    @Test fun contributorTabPagesOnlySelectedSourceWithoutResolvingFinalBodyOrOtherSources() {
        val blocks = mapOf("one" to pages("One"), "two" to pages("Two"))
        val sources = listOf(source("one", "One", blocks.getValue("one")), source("two", "Two", blocks.getValue("two")))
        val finalMessage = MessageV2(id = 2, chatId = 1, linkedMessageId = 1, platformType = "one", content = "", currentRunId = runId, combinedSources = sources, combinedManifest = manifest)
        val calls = Collections.synchronizedList(mutableListOf<Triple<String, Int, Int>>())
        val resolutions = AtomicInteger()
        val finalReads = AtomicInteger()
        var selected by mutableStateOf<String?>("one")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ChatMessagePair(
                        replyRevision = 0, replyVisible = true, onAnswerDisplayed = {}, currentResearchTools = { ChatMcpToolConfig() },
                        messageIndex = 0, message = MessageV2(id = 1, chatId = 1, platformType = null, content = "Compare the explanations"),
                        assistantMessages = listOf(finalMessage), agentRunsById = emptyMap(), activeAgentRuns = emptyMap(), runNoticesById = emptyMap(), toolEventsByRun = emptyMap(),
                        platformIndexState = 0, loadingStates = emptyList(), enabledPlatformsInChat = listOf("one", "two"), enabledPlatformLookup = emptyMap(), disabledPlatformUids = emptySet(), activePlatformUids = setOf("one", "two"),
                        canUseChat = true, isIdle = true, isActiveMessage = false, maximumUserChatBubbleWidth = 400.dp, maximumOpponentChatBubbleWidth = 440.dp,
                        combinedMode = true, selectedCombinedProfileUid = selected, onCombinedProfileClick = { selected = it }, smartSuggestionsEnabled = false,
                        debugSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(researchFeature = ResearchFeatureSettings(articleCards = false)),
                        onEditQuestion = {}, onEditAssistant = { _, _ -> }, onCopyText = {}, onPlatformClick = { _, _ -> }, onPlatformLongPress = {}, onSelectText = {}, onRetry = { _, _ -> },
                        onFavoriteClick = { _, _ -> }, onFavoriteLongPress = {}, onShowPreviousRevision = { _, _ -> }, onShowNextRevision = { _, _ -> },
                        loadCombinedBlocks = { _, _, _ ->
                            finalReads.incrementAndGet()
                            error("Contributor tab must not read the final body")
                        },
                        resolveCombinedMessage = {
                            resolutions.incrementAndGet()
                            error("Contributor tab must not materialize a whole Combined message")
                        },
                        loadCombinedSourceBlocks = { reference, source, first, count ->
                            assertEquals(manifest, reference)
                            assertTrue(count in 1..4)
                            calls += Triple(source.platformUid, first, count)
                            blocks.getValue(source.platformUid).drop(first).take(count)
                        }
                    )
                }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("One page 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("One page 0").assertIsDisplayed()
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.first == "one" })
        assertTrue(calls.sumOf { it.third } < blocks.getValue("one").size)
        assertEquals(0, resolutions.get())
        assertEquals(0, finalReads.get())
        compose.onNodeWithTag("combined_document").performScrollToIndex(8)
        compose.waitUntil(5000) { compose.onAllNodesWithText("One page 8").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("One page 8").assertIsDisplayed()
        assertTrue(calls.any { it.first == "one" && it.second == 8 })
        compose.runOnIdle { selected = "two" }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Two page 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Two page 0").assertIsDisplayed()
        assertEquals(0, resolutions.get())
        assertEquals(0, finalReads.get())
        assertTrue(calls.filter { it.first == "two" }.sumOf { it.third } < blocks.getValue("two").size)
    }

    @Test fun explicitFullReadChecksSourceOnlyDigestAndRetainsEveryPage(): Unit = runBlocking {
        val blocks = pages("One", 9)
        val source = source("one", "One", blocks)
        val requests = mutableListOf<Pair<Int, Int>>()
        val reader = CombinedOriginalSourceReader(source, { selected, first, count ->
            assertEquals("one", selected.platformUid)
            requests += first to count
            blocks.drop(first).take(count)
        })
        assertEquals(blocks.joinToString("") { it.text }, reader.readAll())
        assertEquals(listOf(0 to 4, 4 to 4, 8 to 1), requests)
        val corruptedHeader = source.copy(originalDocument = requireNotNull(source.originalDocument).copy(digest = researchHash("different source")))
        val corrupted = CombinedOriginalSourceReader(corruptedHeader, { _, first, count -> blocks.drop(first).take(count) })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { corrupted.readAll() } }
    }
}
