package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRemovalTest {
    @Test fun idleMappedEngineIsUnloadedBeforeItsFilesAreRemoved() = runBlocking {
        val fake = FakeLocalRuntime()
        val runtime = LocalEngineHolder(fake) { 1000 }
        runtime.loadEngine(LocalEngineSpec("/models/a/local/model.litertlm", "gpu", 1024))
        var removed = false
        withTimeout(2000) {
            runtime.removeModelFilesIfIdle(listOf(File("/models/a"))) {
                assertEquals(1, fake.unloadEngineCalls)
                removed = true
            }
        }
        assertTrue(removed)
        assertEquals(null, runtime.loadedEngineSpec())
    }

    @Test fun activeGenerationIsNeitherCanceledNorQueuedForDeletion() = runBlocking {
        val fake = FakeLocalRuntime()
        val runtime = LocalEngineHolder(fake) { 1000 }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            runtime.runExclusive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var removed = false
        try {
            assertTrue(runCatching { withTimeout(2000) { runtime.removeModelFilesIfIdle(listOf(File("/models/a"))) { removed = true } } }.exceptionOrNull() is IllegalStateException)
            assertFalse(removed)
            assertEquals(0, fake.cancelActiveCalls)
        } finally {
            release.complete(Unit)
            job.join()
        }
    }

    @Test fun modelIdPrefixDoesNotUnloadAnotherInstalledArtifact() = runBlocking {
        val fake = FakeLocalRuntime()
        val runtime = LocalEngineHolder(fake) { 1000 }
        runtime.loadEngine(LocalEngineSpec("/models/ab/local/model.litertlm", "gpu", 1024))
        runtime.removeModelFilesIfIdle(listOf(File("/models/a"))) { }
        assertEquals(0, fake.unloadEngineCalls)
    }
}
