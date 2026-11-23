package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCase
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLegStatus
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonRunner
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonSession
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonStatus
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class PrivacyComparisonLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stoppingRetainedScreenCancelsTheHeldRunAndResumeDoesNotRestart() {
        val owner = ControlledLifecycleOwner()
        val profile = PlatformV2(uid = "synthetic-lifecycle", name = "Synthetic lifecycle profile", model = "fictional-model")
        val opened = mutableListOf<Pair<String, Boolean>>()
        val runIds = mutableListOf<String>()
        val heldStreams = AtomicInteger()
        val cleanedStreams = AtomicInteger()
        val disposals = AtomicInteger()
        var visible by mutableStateOf(true)
        lateinit var model: PrivacyComparisonViewModel
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            model = PrivacyComparisonViewModel(flowOf(listOf(profile))) { frozen, runId, onUpdate ->
                assertEquals(profile, frozen)
                runIds += runId
                PrivacyComparisonRunner(openSession = { caseId, protection, _, _ ->
                    opened += caseId to protection
                    val case = requireNotNull(PrivacyComparisonCase.fromId(caseId))
                    PrivacyComparisonSession(
                        session = object : AgentProviderSession {
                            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                                assertTrue(tools.isEmpty() && exchanges.isEmpty())
                                when {
                                    case == PrivacyComparisonCase.WARMUP -> emit(ProviderEvent.TextDelta("READY"))
                                    case == PrivacyComparisonCase.TRAVEL && !protection -> emit(ProviderEvent.TextDelta(TRAVEL_ANSWER))
                                    else -> {
                                        try {
                                            emit(ProviderEvent.TextDelta("Synthetic held partial answer"))
                                            heldStreams.incrementAndGet()
                                            awaitCancellation()
                                        } finally {
                                            cleanedStreams.incrementAndGet()
                                        }
                                    }
                                }
                                emit(ProviderEvent.Completed)
                            }
                        },
                        policy = if (protection) PrivacyModeState(enabled = true, revision = 7) else null,
                        authorityRevision = "synthetic-lifecycle-authority"
                    )
                }).run(runId, onUpdate)
            }
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (visible) {
                                DisposableEffect(Unit) { onDispose { disposals.incrementAndGet() } }
                                PrivacyComparisonPanel(viewModel = model)
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("privacy_comparison_toggle").performClick()
            compose.onNodeWithTag("privacy_comparison_profile").performClick()
            compose.onNodeWithTag("privacy_comparison_profile_${profile.uid}").performClick()
            compose.onNodeWithTag("privacy_comparison_run").assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { heldStreams.get() == 1 }
            compose.runOnIdle {
                assertTrue(model.state.value.running)
                assertEquals(listOf("warmup" to false, "warmup" to true, "travel" to false, "travel" to true), opened)
                // The composition and ViewModel remain mounted while this emits ON_STOP.
                owner.lifecycle.currentState = Lifecycle.State.CREATED
            }
            compose.waitUntil(timeoutMillis = 5_000) { cleanedStreams.get() == 1 && !model.state.value.running }
            compose.onNodeWithTag("privacy_comparison_toggle").assertExists()
            compose.runOnIdle {
                assertEquals(0, disposals.get())
                assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
                val result = requireNotNull(model.state.value.result)
                assertEquals(PrivacyComparisonStatus.CANCELLED, result.status)
                assertEquals(2, result.legs.size)
                assertTrue(result.legs.first().taskCriteriaPassed)
                assertEquals(PrivacyComparisonLegStatus.CANCELLED, result.legs.last().status)
                assertEquals("Synthetic held partial answer", result.legs.last().answer)
                assertTrue(result.legs.last().criteria.isEmpty())
                assertEquals(4, opened.size)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            compose.onNodeWithText("Comparison cancelled; recorded responses retained").assertExists()
            compose.onNodeWithTag("privacy_comparison_pair_status_travel").assertTextContains("Pair incomplete.")
            compose.runOnIdle {
                assertFalse(model.state.value.running)
                assertEquals(1, runIds.size)
                assertEquals(4, opened.size)
                assertEquals(0, disposals.get())
            }

            // Only a new explicit Run may start again; disposal still owns cancellation.
            compose.onNodeWithTag("privacy_comparison_run").assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { heldStreams.get() == 2 }
            compose.runOnIdle {
                assertEquals(2, runIds.toSet().size)
                assertEquals(8, opened.size)
                visible = false
            }
            compose.waitUntil(timeoutMillis = 5_000) { cleanedStreams.get() == 2 && !model.state.value.running }
            compose.waitForIdle()
            compose.onNodeWithTag("privacy_comparison_toggle").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, disposals.get())
                assertEquals(0, owner.lifecycle.observerCount)
                assertEquals(PrivacyComparisonStatus.CANCELLED, model.state.value.result?.status)
                assertEquals(2, model.state.value.result?.legs?.size)
                assertEquals(8, opened.size)
            }
        } finally {
            compose.runOnUiThread { model.viewModelScope.cancel() }
        }
    }

    private class ControlledLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private companion object {
        const val TRAVEL_ANSWER = """{"traveler":"Mira Voss","companion":"Elian Reed","departure":"February 4th","plan":["Visit a quiet park","Explore a small museum","Take a gentle walking tour"]}"""
    }
}
