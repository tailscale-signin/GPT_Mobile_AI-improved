package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp-mdpi")
class GitHubApprovalSummaryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextKeepsAccessibleActionsHorizontalAndBusyBlocksWrites() {
        var busy by mutableStateOf(false)
        var commits = 0
        var details = 0
        var rejects = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        GitHubApprovalSummary("Three files will be updated. Fix viewport sizing.", busy, true, "", { commits++ }, { details++ }, { rejects++ })
                    }
                }
            }
        }
        val commit = compose.onNodeWithContentDescription("Approve and commit proposed changes")
        val detail = compose.onNodeWithContentDescription("View proposed change details")
        val reject = compose.onNodeWithContentDescription("Reject proposed changes")
        listOf(commit, detail, reject).forEach { it.assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        val a = commit.fetchSemanticsNode().boundsInRoot
        val b = detail.fetchSemanticsNode().boundsInRoot
        val c = reject.fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top, 1f)
        assertEquals(b.top, c.top, 1f)
        assertTrue(a.right <= b.left && b.right <= c.left)
        compose.runOnIdle { busy = true }
        commit.assertIsNotEnabled()
        reject.assertIsNotEnabled()
        detail.performClick()
        compose.runOnIdle {
            assertEquals(0, commits)
            assertEquals(0, rejects)
            assertEquals(1, details)
            busy = false
        }
        commit.performClick()
        compose.runOnIdle { assertEquals(1, commits) }
    }
}
