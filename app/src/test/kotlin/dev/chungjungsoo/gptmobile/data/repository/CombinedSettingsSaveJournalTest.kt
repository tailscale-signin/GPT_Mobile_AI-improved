package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveOperation
import dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSettingsSaveJournalTest {
    private val previous = CombinedChatSettingsDraft("identity", 4, 0, participantUids = listOf("a"), models = mapOf("a" to "old"), creativity = 0.5f, operationId = "old")
    private val requested = previous.copy(settings = ConversationCombinedSettings(collectionBias = 100, effort = 75), participantUids = listOf("a", "b"), models = mapOf("a" to "new", "b" to "peer"), creativity = 0.9f, operationId = "save")

    @Test fun successfulSaveCommitsAllOwnedValuesOnceAndKeepsImmediateChanges() = runBlocking<Unit> {
        val store = MemorySettings()
        var room = previous
        val journal = CombinedSettingsSaveJournal(store)
        val operation = journal.save(requested, previous) { before, next ->
            assertEquals(before, room)
            store.value = store.value.copy(edgeFades = false)
            room = next
        }
        assertEquals(CombinedSettingsSavePhase.COMMITTED, operation.phase)
        assertEquals(requested.validated(), room)
        assertEquals(100, store.value.conversationCombined.getValue(4).collectionBias)
        assertEquals(75, store.value.conversationCombined.getValue(4).effort)
        assertEquals(0.9f, store.value.conversationCombined.getValue(4).creativity)
        assertEquals(1L, store.value.conversationCombined.getValue(4).revision)
        assertFalse(store.value.edgeFades)
        journal.recover(4) { _, _ -> error("Committed saves must not replay") }
    }

    @Test fun failedCommitRollsBackAndAbortCannotLaterPublishDiscardedDraft() = runBlocking<Unit> {
        val store = MemorySettings().apply { failCommit = true }
        var room = previous
        val journal = CombinedSettingsSaveJournal(store)
        assertTrue(runCatching { journal.save(requested, previous) { _, next -> room = next } }.isFailure)
        assertEquals(previous, room)
        assertTrue(store.value.conversationCombined.isEmpty())
        assertEquals(CombinedSettingsSavePhase.FAILED, store.value.combinedSettingsJournal.getValue(4).phase)
        journal.abort(4, requested.operationId) { _, next -> room = next }
        store.failCommit = false
        journal.recover(4) { _, _ -> error("Aborted saves must never commit") }
        assertEquals(CombinedSettingsSavePhase.ABORTED, store.value.combinedSettingsJournal.getValue(4).phase)
        assertEquals(previous, room)
    }

    @Test fun interruptedRoomWriteRecoversIdempotentlyThroughCommittedFacade() = runBlocking<Unit> {
        val pending = CombinedSettingsSaveOperation("save", "identity", 4, previous, requested)
        val store = MemorySettings().apply { value = value.copy(combinedSettingsJournal = mapOf(4 to pending)) }
        var room = requested
        assertEquals(previous, pending.committedView(room))
        val journal = CombinedSettingsSaveJournal(store)
        var writes = 0
        journal.recover(4) { before, next ->
            if (room != next) {
                assertEquals(before, room)
                room = next
                writes++
            }
        }
        journal.recover(4) { _, _ -> error("Do not replay a committed transaction") }
        assertEquals(0, writes)
        assertEquals(1L, store.value.conversationCombined.getValue(4).revision)
    }

    @Test fun concurrentStaleSavesCannotOverwriteOneAnother() = runBlocking<Unit> {
        val store = MemorySettings()
        val journal = CombinedSettingsSaveJournal(store)
        val first = async { journal.save(requested, previous) { _, _ -> yield() } }
        val second = async { runCatching { journal.save(requested.copy(operationId = "other", settings = ConversationCombinedSettings(effort = 0)), previous) { _, _ -> error("Stale draft must not reach Room") } } }
        first.await()
        assertTrue(second.await().isFailure)
        assertEquals(75, store.value.conversationCombined.getValue(4).effort)
    }

    @Test fun invalidOrFutureDraftCannotStartAWritableJournal() = runBlocking<Unit> {
        val store = MemorySettings()
        val journal = CombinedSettingsSaveJournal(store)
        assertTrue(runCatching { journal.save(requested.copy(settings = ConversationCombinedSettings(schemaVersion = 5)), previous) { _, _ -> error("No dispatch") } }.isFailure)
        assertTrue(runCatching { journal.save(requested.copy(participantUids = emptyList()), previous) { _, _ -> error("No dispatch") } }.isFailure)
        assertTrue(store.value.combinedSettingsJournal.isEmpty())
    }

    @Test fun restoreAndDeletionBarrierPreventsAnOldDraftFromTouchingAReplacedRoom() = runBlocking<Unit> {
        val store = MemorySettings()
        val barrierEntered = CompletableDeferred<Unit>()
        val finishReplacement = CompletableDeferred<Unit>()
        val replacement = async {
            CombinedSettingsSaveJournal.withExclusiveMutation {
                barrierEntered.complete(Unit)
                finishReplacement.await()
                store.value = store.value.copy(conversationIdentities = mapOf(4 to "replacement"))
            }
        }
        barrierEntered.await()
        var roomTouched = false
        val save = async { runCatching { CombinedSettingsSaveJournal(store).save(requested, previous) { _, _ -> roomTouched = true } } }
        yield()
        assertFalse(roomTouched)
        assertFalse(save.isCompleted)
        finishReplacement.complete(Unit)
        replacement.await()
        assertTrue(save.await().isFailure)
        assertFalse(roomTouched)
        assertEquals("replacement", store.value.conversationIdentities[4])
    }

    private class MemorySettings : SettingRepository by unusedRepository() {
        var value = AppFeatureSettings(conversationIdentities = mapOf(4 to "identity"))
        var failCommit = false
        override suspend fun getFeatureSettings() = value
        override suspend fun mutateFeatureSettings(transform: (AppFeatureSettings) -> AppFeatureSettings): AppFeatureSettings {
            val next = transform(value)
            if (failCommit && next.combinedSettingsJournal[4]?.phase == CombinedSettingsSavePhase.COMMITTED) error("Simulated DataStore failure after Room")
            value = next
            return next
        }
    }

    companion object {
        private fun unusedRepository(): SettingRepository = Proxy.newProxyInstance(SettingRepository::class.java.classLoader, arrayOf(SettingRepository::class.java)) { _, method, _ -> error("Unexpected repository call: ${method.name}") } as SettingRepository
    }
}
