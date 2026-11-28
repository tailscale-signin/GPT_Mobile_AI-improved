package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceOutputConfigurationEffectTest {
    private fun factory(name: String) = object : VoicePhraseEngineFactory {
        override val identity = name
        override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Configuration must not create an engine")
    }

    private class RecordingOutput : KokoroOutput {
        val configured = mutableListOf<String>()
        override fun configureEngine(factory: VoicePhraseEngineFactory) {
            configured += factory.identity
        }
        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit): Unit = throw AssertionError("Configuration must not dispatch speech")
        override fun stop() = Unit
        override fun close() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoringTheScreenWaitsForPreferencesBeforeTouchingTheRetainedVoice() = runTest {
        val ordinary = factory("ordinary-english")
        val translated = factory("accepted-french-estelle")
        val nextTranslation = factory("accepted-english-azelma")
        val output = RecordingOutput()
        val ready = mutableStateOf(true)
        val selected = mutableStateOf(translated)
        val shown = mutableStateOf(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val composition = Composition(applier, recomposer)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        fun frame() {
            testScheduler.runCurrent()
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            clock.sendFrame(testScheduler.currentTime * 1_000_000)
            testScheduler.runCurrent()
        }
        try {
            composition.setContent {
                if (shown.value) VoiceOutputConfigurationEffect(ready.value, output, selected.value)
            }
            frame()
            assertEquals(listOf(translated.identity), output.configured)

            // Navigation/recreation removes the effect while the runtime retains its output.
            shown.value = false
            frame()
            ready.value = false
            selected.value = ordinary
            shown.value = true
            frame()
            assertEquals("Unloaded defaults must not stop the accepted translated reply", listOf(translated.identity), output.configured)

            // The accepted factory may be available before preference hydration completes.
            selected.value = translated
            frame()
            assertEquals(listOf(translated.identity), output.configured)
            ready.value = true
            frame()
            assertEquals(listOf(translated.identity, translated.identity), output.configured)

            selected.value = nextTranslation
            frame()
            selected.value = ordinary
            frame()
            assertEquals(listOf(translated.identity, translated.identity, nextTranslation.identity, ordinary.identity), output.configured)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }
}
