package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitHubRenderedLinesLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wrappingAndSimultaneousWiderWindowUseActualTextLayoutResults() {
        val retained = "The existing paragraph contains enough words to wrap across several visual lines at a narrow width."
        var text by mutableStateOf(retained)
        var width by mutableStateOf(110.dp)
        lateinit var lines: GitHubRenderedLines
        lateinit var actual: TextLayoutResult
        lateinit var oldTextAtCurrentWidth: TextLayoutResult
        compose.setContent {
            MaterialTheme {
                lines = remember { GitHubRenderedLines() }
                Column(Modifier.width(width)) {
                    // Independent reference rendered by the same real Text primitive.
                    Text(retained, onTextLayout = { oldTextAtCurrentWidth = it })
                    GitHubLineScope(lines, "turn") {
                        val reportLayout = rememberGitHubTextLayout("paragraph")
                        Text(text, onTextLayout = {
                            actual = it
                            reportLayout(it)
                        })
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(actual.lineCount > 1)
            lines.ledger.arm()
            width = 90.dp
        }
        compose.runOnIdle {
            assertEquals(0, lines.ledger.takeLines())
            width = 280.dp
            text += " Newly generated words must keep their own reading time even when the original paragraph reflows into fewer lines at the same moment."
        }
        compose.runOnIdle {
            val added = actual.lineCount - oldTextAtCurrentWidth.lineCount
            assertTrue(added > 0)
            assertEquals(added, lines.ledger.takeLines())
        }
    }
}
