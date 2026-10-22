package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
