package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoicePreferenceWriterTest {
    @Test fun slowOldWriteCannotOverwriteNewerPrivacySettings() = runTest {
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val writer = VoicePreferenceWriter<Boolean>(backgroundScope) { online ->
            if (online) release.await()
            persisted += online
        }
        writer.save(true)
        runCurrent()
        writer.save(false)
        runCurrent()
        assertEquals(emptyList<Boolean>(), persisted)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), persisted)
    }

    @Test fun rapidChangesKeepOnlyNewestPendingValue() = runTest {
        val persisted = mutableListOf<Int>()
        val writer = VoicePreferenceWriter<Int>(backgroundScope) { persisted += it }
        repeat(1000) { writer.save(it) }
        runCurrent()
        assertEquals(listOf(999), persisted)
    }

    @Test fun disposalRejectsNewWritesWithoutThrowing() = runTest {
        val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val persisted = mutableListOf<Int>()
        val writer = VoicePreferenceWriter<Int>(scope) { persisted += it }
        scope.cancel()
        runCurrent()
        writer.save(1)
        runCurrent()
        assertEquals(emptyList<Int>(), persisted)
    }
}
