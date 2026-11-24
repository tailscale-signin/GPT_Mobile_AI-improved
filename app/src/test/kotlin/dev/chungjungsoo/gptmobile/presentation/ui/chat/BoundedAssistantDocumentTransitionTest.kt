package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class BoundedAssistantDocumentTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changedRevisionHidesTheOldPageUntilTheNewBoundedReadCompletes() {
        var reference by mutableStateOf(CombinedManifestReference("document", "run", "old", 8, 1, researchHash("old page")))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                BoundedAssistantDocument(
                    identity = "same-bubble",
                    reference = reference,
                    loadBlocks = { selected, first, count ->
                        assertEquals(0, first)
                        assertEquals(4, count)
                        reads += selected.revisionId
                        if (selected.revisionId == "new") {
                            entered.complete(Unit)
                            release.await()
                        }
                        val text = "${selected.revisionId} page"
                        listOf(CombinedDocumentBlock(0, 0, text, researchHash(text), emptyList()))
                    },
                    wording = null,
                    proseColor = null,
                    articleCards = { Text("loaded:$it") }
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("loaded:old page").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { reference = reference.copy(revisionId = "new", bodyDigest = researchHash("new page")) }
        // Lazy item recomposition also needs Android layout; an external deferred
        // predicate alone does not drain Robolectric's pending platform work.
        compose.waitForIdle()
        compose.waitUntil(5_000) { entered.isCompleted }
        compose.onNodeWithText("loaded:old page").assertDoesNotExist()
        compose.onNodeWithText("loaded:new page").assertDoesNotExist()
        release.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("loaded:new page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("loaded:old page").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("old", "new"), reads) }
    }
}
