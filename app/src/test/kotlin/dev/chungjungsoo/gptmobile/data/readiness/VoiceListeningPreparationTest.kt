package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceListeningPreparationTest {
    @Test fun slowDiscoveryIsSharedAcrossCapturesWhileCorrectedWordsReachLocalSearch() = runBlocking {
        val phases = MutableStateFlow(VoiceState())
        val discoveries = AtomicInteger()
        val starting = CompletableDeferred<Unit>()
        val memory = CompletableDeferred<Unit>()
        val searches = Channel<String>(Channel.UNLIMITED)
        val session = launch(start = CoroutineStart.UNDISPATCHED) {
            prepareWhileListening(phases, {
                discoveries.incrementAndGet()
                starting.complete(Unit)
                awaitCancellation()
            }, { memory.complete(Unit) }, { searches.send(it) })
        }
        assertEquals(0, discoveries.get())
        phases.value = VoiceState(phase = VoicePhase.STARTING)
        withTimeout(4_000) {
            starting.await()
            memory.await()
        }
        phases.value = VoiceState(phase = VoicePhase.LISTENING, transcript = "Find the costume")
        phases.value = phases.value.copy(transcript = "Find the customer")
        assertEquals("Find the customer", withTimeout(4_000) { searches.receive() })
        phases.value = VoiceState(phase = VoicePhase.THINKING)
        kotlinx.coroutines.yield()
        phases.value = VoiceState(phase = VoicePhase.LISTENING, transcript = "My second question")
        assertEquals("My second question", withTimeout(4_000) { searches.receive() })
        phases.value = VoiceState(phase = VoicePhase.THINKING, awaitingReply = true, transcript = "My corrected final question?")
        assertEquals("My corrected final question?", withTimeout(4_000) { searches.receive() })
        assertEquals(1, discoveries.get())
        session.cancelAndJoin()
    }
}
