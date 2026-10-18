package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveOperation
import dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable cross-store commit; Room callbacks must compare-and-apply only owned fields. */
class CombinedSettingsSaveJournal(private val settings: SettingRepository) {
    suspend fun save(
        draft: CombinedChatSettingsDraft,
        previous: CombinedChatSettingsDraft,
        applyConversation: suspend (CombinedChatSettingsDraft, CombinedChatSettingsDraft) -> Unit
    ): CombinedSettingsSaveOperation = mutations.withLock {
        val next = draft.validated()
        require(next.chatId > 0 && next.chatId == previous.chatId && next.conversationIdentity == previous.conversationIdentity)
        val operation = CombinedSettingsSaveOperation(next.operationId, next.conversationIdentity, next.chatId, previous, next)
        val stored = settings.mutateFeatureSettings { current ->
            check(next.chatId !in current.combinedSettingsDeletions) { "This conversation is being deleted." }
            val priorOperation = current.combinedSettingsJournal[next.chatId]
            if (priorOperation?.operationId == next.operationId) {
                check(priorOperation.requested == next) { "A settings operation cannot change after it is accepted." }
                return@mutateFeatureSettings current
            }
            check(priorOperation == null || priorOperation.roomSettled) { "Recover or cancel the earlier settings save first." }
            check(current.conversationIdentities[next.chatId] == next.conversationIdentity) { "This conversation changed while settings were open." }
            val committed = current.conversationCombined[next.chatId] ?: ConversationCombinedSettings()
            check(committed.isSupported && committed.revision == next.baseRevision) { "Conversation settings changed. Reopen settings before saving." }
            current.copy(combinedSettingsJournal = current.combinedSettingsJournal + (next.chatId to operation))
        }.combinedSettingsJournal.getValue(next.chatId)
        complete(stored, applyConversation)
    }

    suspend fun recover(
        chatId: Int,
        applyConversation: suspend (CombinedChatSettingsDraft, CombinedChatSettingsDraft) -> Unit
    ): CombinedSettingsSaveOperation? = mutations.withLock {
        val operation = settings.getFeatureSettings().combinedSettingsJournal[chatId] ?: return@withLock null
        if (operation.phase == CombinedSettingsSavePhase.ABORTED) {
            settleAbort(operation, applyConversation)
        } else {
            complete(operation, applyConversation)
        }
    }

    suspend fun abort(
        chatId: Int,
        operationId: String,
        applyConversation: suspend (CombinedChatSettingsDraft, CombinedChatSettingsDraft) -> Unit
    ): CombinedSettingsSaveOperation? = mutations.withLock {
        val stored = settings.getFeatureSettings().combinedSettingsJournal[chatId] ?: return@withLock null
        if (stored.operationId != operationId || stored.phase == CombinedSettingsSavePhase.COMMITTED) return@withLock stored
        val aborted = update(stored.copy(phase = CombinedSettingsSavePhase.ABORTED))
        settleAbort(aborted, applyConversation)
    }

    private suspend fun complete(
        operation: CombinedSettingsSaveOperation,
        applyConversation: suspend (CombinedChatSettingsDraft, CombinedChatSettingsDraft) -> Unit
    ): CombinedSettingsSaveOperation {
        if (operation.phase == CombinedSettingsSavePhase.COMMITTED || operation.phase == CombinedSettingsSavePhase.ABORTED) return operation
        val pending = update(operation.copy(phase = CombinedSettingsSavePhase.COMMITTING, roomSettled = false, error = null))
        try {
            applyConversation(pending.previous, pending.requested)
            return settings.mutateFeatureSettings { current ->
                check(current.conversationIdentities[pending.chatId] == pending.conversationIdentity)
                check(current.combinedSettingsJournal[pending.chatId]?.operationId == pending.operationId)
                check(current.combinedSettingsJournal[pending.chatId]?.phase == CombinedSettingsSavePhase.COMMITTING)
                val previous = current.conversationCombined[pending.chatId] ?: ConversationCombinedSettings()
                check(previous.isSupported && previous.revision == pending.requested.baseRevision) { "Settings revision changed during commit." }
                val accepted = pending.requested.settings.copy(creativity = pending.requested.creativity, revision = Math.addExact(previous.revision, 1)).normalized()
                current.copy(
                    conversationCombined = current.conversationCombined + (pending.chatId to accepted),
                    combinedSettingsJournal = current.combinedSettingsJournal + (pending.chatId to pending.copy(phase = CombinedSettingsSavePhase.COMMITTED, roomSettled = true))
                )
            }.combinedSettingsJournal.getValue(pending.chatId)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val rolledBack = runCatching { applyConversation(pending.requested, pending.previous) }.isSuccess
                update(pending.copy(phase = CombinedSettingsSavePhase.FAILED, error = error.message ?: "Settings could not be saved.", roomSettled = rolledBack))
            }
            throw error
        }
    }

    private suspend fun settleAbort(
        operation: CombinedSettingsSaveOperation,
        applyConversation: suspend (CombinedChatSettingsDraft, CombinedChatSettingsDraft) -> Unit
    ): CombinedSettingsSaveOperation {
        if (operation.roomSettled) return operation
        applyConversation(operation.requested, operation.previous)
        return update(operation.copy(roomSettled = true))
    }

    private suspend fun update(operation: CombinedSettingsSaveOperation): CombinedSettingsSaveOperation =
        settings.mutateFeatureSettings { current ->
            check(current.combinedSettingsJournal[operation.chatId]?.operationId == operation.operationId) { "A newer settings save owns this conversation." }
            current.copy(combinedSettingsJournal = current.combinedSettingsJournal + (operation.chatId to operation))
        }.combinedSettingsJournal.getValue(operation.chatId)

    companion object {
        private val mutations = Mutex()

        /** Acquire before Room in backup, restore and deletion so accepted saves cannot race replacement. */
        suspend fun <T> withExclusiveMutation(block: suspend () -> T): T = mutations.withLock { block() }
    }
}
