package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AdaptiveAnswerTypographyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun streamedAnswerTransitionsToRegularSizeWithoutShrinkingAccessibilityFloor() {
        val length = mutableIntStateOf(40)
        var observed = 0f
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAnswerPane provides AnswerPane(360f, 720f)) {
                    Box(Modifier.width(320.dp)) {
                        AdaptiveAnswerTypography("A short answer.", length.intValue) {
                            observed = LocalAnswerTextScale.current
                            Text("Answer")
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(observed > 1f)
            length.intValue = 2000
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, observed, 0.001f) }
    }

    @Test fun codeKeepsRegularSize() {
        var observed = 0f
        compose.setContent {
            MaterialTheme {
                AdaptiveAnswerTypography("```kotlin\nval x = 1\n```", 30) {
                    observed = LocalAnswerTextScale.current
                    Text("Code")
                }
            }
        }
        compose.runOnIdle { assertEquals(1f, observed, 0.001f) }
    }

    @Test fun disablingAdaptiveAnswersRestoresRegularText() {
        val enabled = mutableStateOf(true)
        var observed = 0f
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAdaptiveAnswerText provides enabled.value) {
                    Box(Modifier.width(320.dp)) {
                        AdaptiveAnswerTypography("Brief reply", 11) {
                            observed = LocalAnswerTextScale.current
                            Text("Answer")
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(observed > 1f)
            enabled.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, observed, 0.001f) }
    }
}
