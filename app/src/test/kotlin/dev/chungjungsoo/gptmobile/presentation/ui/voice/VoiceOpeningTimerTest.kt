package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoiceOpeningPlan
import dev.chungjungsoo.gptmobile.data.voice.VoiceThinkingCue
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
class VoiceOpeningTimerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFastAnswerCancelsTheTimerAndARealLookupReplacesTheThinkingCue() = runTest {
        val identity = VoiceMessageIdentity(5, "r")
        val thinking = VoiceOpeningPlan(identity, VoiceThinkingCue("Un instant."))
        val lookup = VoiceOpeningPlan(identity, VoiceThinkingCue("Je consulte les sources.", lookup = true))
        val pending = mutableStateOf<VoiceOpeningPlan?>(thinking)
        val announced = mutableListOf<VoiceOpeningPlan>()
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
                val due = rememberDueVoiceOpening(pending.value)
                SideEffect { if (due != null) announced.add(due) }
            }
            frame()
            testScheduler.advanceTimeBy(300)
            pending.value = null
            frame()
            testScheduler.advanceTimeBy(1_000)
            frame()
            assertEquals(emptyList<VoiceOpeningPlan>(), announced)

            pending.value = thinking
            frame()
            testScheduler.advanceTimeBy(400)
            pending.value = lookup
            frame()
            testScheduler.advanceTimeBy(lookup.delayMs - 1)
            frame()
            assertEquals(emptyList<VoiceOpeningPlan>(), announced)
            testScheduler.advanceTimeBy(1)
            frame()
            pending.value = null
            frame()
            testScheduler.advanceTimeBy(thinking.delayMs)
            frame()
            assertEquals(listOf(lookup), announced)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }
}
