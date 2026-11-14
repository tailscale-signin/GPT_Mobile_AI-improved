package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import dev.chungjungsoo.gptmobile.presentation.ui.chat.gitHubTopFadeHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubChatNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun measuredExpansionAndCollapseReachTheSameRetainedConversation() {
        var measuredHeader by mutableStateOf(48.dp)
        var binding by mutableStateOf("original")
        lateinit var projection: Projection
        var density = 1f
        compose.setContent {
            MaterialTheme {
                Scaffold(
                    modifier = Modifier.size(320.dp, 600.dp).testTag("workspace"),
                    topBar = { Box(Modifier.height(measuredHeader)) }
                ) { padding ->
                    val capturedBinding = binding
                    GitHubChatNavigation(9, "profile", padding.calculateTopPadding()) { headerHeight ->
                        val identity = remember { Any() }
                        val entry = LocalViewModelStoreOwner.current as NavBackStackEntry
                        density = LocalDensity.current.density
                        SideEffect {
                            projection = Projection(identity, entry, headerHeight, gitHubTopFadeHeight(headerHeight.value, 800f, true), capturedBinding)
                        }
                        Box(Modifier.fillMaxSize().padding(top = headerHeight)) {
                            Box(Modifier.size(1.dp).testTag("first_message"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val original = projection
        assertEquals(9, requireNotNull(original.entry.arguments).getInt("chatRoomId"))
        assertEquals("profile", original.entry.arguments?.getString("enabledPlatforms"))
        assertEquals("STANDARD", original.entry.arguments?.getString("conversationMode"))
        for (height in listOf(48.dp, 96.dp, 144.dp, 96.dp, 48.dp)) {
            compose.runOnIdle {
                measuredHeader = height
                binding = "header:${height.value}"
            }
            compose.waitForIdle()
            assertSame(original.identity, projection.identity)
            assertSame(original.entry, projection.entry)
            assertEquals(height, projection.headerHeight)
            assertEquals(height.value + 24f, projection.fadeHeight, 0f)
            assertEquals("header:${height.value}", projection.binding)
            val workspace = compose.onNodeWithTag("workspace").fetchSemanticsNode().boundsInRoot
            val message = compose.onNodeWithTag("first_message").fetchSemanticsNode().boundsInRoot
            assertEquals(height.value * density, message.top - workspace.top, 1f)
        }
    }

    private data class Projection(
        val identity: Any,
        val entry: NavBackStackEntry,
        val headerHeight: Dp,
        val fadeHeight: Float,
        val binding: String
    )
}
