package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Index synchronization is independent of chat generation and never dispatches inference. */
@Singleton
class GatewayIndexSyncService @Inject constructor(
    private val settings: SettingRepository,
    private val rooms: ChatRoomV2Dao,
    private val graphs: GatewayGraphRepository
) {
    private val synchronization = Mutex()
    private var nextRepository = 0

    suspend fun synchronizeKnownRepositories() = synchronization.withLock {
        val features = settings.getFeatureSettings()
        if (!features.githubBlobCache) return@withLock
        // Private/temporary conversations need their request-scoped privacy
        // authority. An unrelated network callback must not reconstruct it.
        val eligible = rooms.getChatRooms().filter { !it.isTemporary && !it.privacyModeEnabled && !it.privacyModeLocked }
            .sortedByDescending { it.updatedAt }.mapNotNull { room -> features.conversationGitHub[room.id]?.let { room.id to it } }
            .distinctBy { (_, context) -> listOf(context.connectionUid, context.owner.lowercase(), context.repo.lowercase(), context.ref, context.mainProfileUid) }
        if (eligible.isEmpty()) return@withLock
        repeat(minOf(4, eligible.size)) {
            val (roomId, selected) = eligible[nextRepository % eligible.size]
            nextRepository = (nextRepository + 1) % eligible.size
            try {
                withTimeoutOrNull(12_000) {
                    graphs.synchronize(selected) {
                        val current = rooms.getChatRoom(roomId)
                        val live = settings.getFeatureSettings()
                        current != null &&
                            !current.isTemporary &&
                            !current.privacyModeEnabled &&
                            !current.privacyModeLocked &&
                            live.githubBlobCache &&
                            live.conversationGitHub[roomId] == selected
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Index presence never grants access. Admission is rechecked on
                // every page; do not expose private paths/hosts in diagnostics.
                AppLogRecorder.record("RepositoryIndex", "SYNC_DEFERRED · reason=${failure.javaClass.simpleName} · previous_snapshot=retained", "W")
            }
        }
    }
}
