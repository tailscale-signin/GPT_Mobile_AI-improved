package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedCollectionDecisionStoreTest {
    @Test fun convertingToTemporaryRevokesPersistenceEvenForAnOlderCallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("combined_collection_decisions", Context.MODE_PRIVATE).edit().clear().commit()
        val store = CombinedCollectionDecisionStore(context)
        store.acknowledge("converted", setOf("first"))
        store.setPersistent(setOf("converted"), false)
        store.acknowledge("converted", setOf("late"), persistent = true)
        val temporaryRestart = CombinedCollectionDecisionStore(context)
        temporaryRestart.load()
        assertTrue(temporaryRestart.decisions.value.isEmpty())
        assertTrue(store.acknowledged("converted", "late"))
        store.setPersistent(setOf("converted"), true)
        val normalRestart = CombinedCollectionDecisionStore(context)
        normalRestart.load()
        assertTrue(normalRestart.acknowledged("converted", "late"))
    }

    @Test fun acknowledgementsMergeDurablyButDoNotAuthorizeNewAttempts() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("combined_collection_decisions", Context.MODE_PRIVATE).edit().clear().commit()
        val store = CombinedCollectionDecisionStore(context)
        (1..8).map { index -> async { store.acknowledge("root", setOf("attempt-$index")) } }.awaitAll()
        store.chooseWriter("root", "writer")
        val restored = CombinedCollectionDecisionStore(context)
        restored.load()
        assertEquals(8, restored.decisions.value.getValue("root").acknowledgedAttempts.size)
        assertEquals("writer", restored.chosenWriter("root"))
        assertTrue(restored.acknowledged("root", "attempt-1"))
        assertEquals(setOf("retry-attempt"), restored.pending("root", setOf("attempt-1", "retry-attempt")))
        assertFalse(restored.acknowledged("another-root", "attempt-1"))
    }

    @Test fun temporaryDecisionsStayInMemoryAndDeletedRootsDoNotRestore() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("combined_collection_decisions", Context.MODE_PRIVATE).edit().clear().commit()
        val store = CombinedCollectionDecisionStore(context)
        store.acknowledge("temporary", setOf("temp-attempt"), persistent = false)
        store.chooseWriter("temporary", "private-profile", persistent = false)
        store.acknowledge("saved", setOf("saved-attempt"))
        assertTrue(store.acknowledged("temporary", "temp-attempt"))
        val restored = CombinedCollectionDecisionStore(context)
        restored.load()
        assertFalse("temporary" in restored.decisions.value)
        assertTrue("saved" in restored.decisions.value)
        restored.deleteRoots(setOf("saved"))
        assertTrue(runCatching { restored.acknowledge("saved", setOf("late-attempt")) }.isFailure)
        assertTrue(runCatching { restored.chooseWriter("saved", "late-writer") }.isFailure)
        val deleted = CombinedCollectionDecisionStore(context)
        deleted.load()
        assertTrue(deleted.decisions.value.isEmpty())
    }
}
