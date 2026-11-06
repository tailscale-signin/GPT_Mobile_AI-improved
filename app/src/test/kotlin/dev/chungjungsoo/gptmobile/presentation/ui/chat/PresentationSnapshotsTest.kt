package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationSnapshotsTest {
    private data class Snapshot(val text: String, val live: Boolean)

    @Test fun firstUsefulTextAndTerminalTailBypassThePresentationTimer() = runTest {
        val received = mutableListOf<Pair<Long, Snapshot>>()
        flow {
            emit(Snapshot("", true))
            emit(Snapshot("first", true))
            delay(5)
            emit(Snapshot("first pending", true))
            delay(10)
            emit(Snapshot("first pending tail", false))
        }.presentationSnapshots(streaming = { it.live }, useful = { it.text.isNotEmpty() }).collect { received += currentTime to it }
        assertEquals(listOf(0L, 15L), received.map { it.first })
        assertEquals(listOf("first", "first pending tail"), received.map { it.second.text })
    }

    @Test fun burstSnapshotsStayOrderedAndBoundedWhilePreservingTheExactFinalBody() = runTest {
        val raw = StringBuilder()
        val received = mutableListOf<Pair<Long, Snapshot>>()
        flow {
            repeat(160) { index ->
                raw.append("$index🙂\n")
                emit(Snapshot(raw.toString(), true))
                delay(1)
            }
            emit(Snapshot(raw.toString(), false))
        }.presentationSnapshots(streaming = { it.live }, useful = { it.text.isNotEmpty() }).collect { received += currentTime to it }
        assertEquals(0L, received.first().first)
        assertEquals(160L, received.last().first)
        assertEquals(raw.toString(), received.last().second.text)
        assertTrue(received.size in 4..7)
        assertTrue(received.zipWithNext().all { (previous, next) -> next.second.text.startsWith(previous.second.text) })
        assertTrue(received.dropLast(1).zipWithNext().all { (previous, next) -> next.first - previous.first in 33..50 })
    }

    @Test fun incompleteStreamFlushesItsPendingSnapshotWithoutInventingCompletion() = runTest {
        val received = flow {
            emit(Snapshot("first", true))
            delay(5)
            emit(Snapshot("first partial", true))
        }.presentationSnapshots(streaming = { it.live }, useful = { it.text.isNotEmpty() }).toList()
        assertEquals(listOf("first", "first partial"), received.map { it.text })
        assertTrue(received.last().live)
        assertEquals(5L, currentTime)
    }

    @Test fun aNewStreamingAttemptPublishesItsFirstUsefulTextImmediately() = runTest {
        val times = mutableListOf<Long>()
        flow {
            emit(Snapshot("saved answer", false))
            delay(10)
            emit(Snapshot("new answer", true))
            delay(5)
            emit(Snapshot("new answer complete", false))
        }.presentationSnapshots(streaming = { it.live }, useful = { it.text.isNotEmpty() }).collect { times += currentTime }
        assertEquals(listOf(0L, 10L, 15L), times)
    }
}
