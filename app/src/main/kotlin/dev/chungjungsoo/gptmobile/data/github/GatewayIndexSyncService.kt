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
                val completed = withTimeoutOrNull(12_000) {
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
                if (completed != true) AppLogRecorder.recordEvent("RepositoryIndex", "SYNC_DEFERRED", GatewayIndexSyncObservation.deferred(completed), "W")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Index presence never grants access. Admission is rechecked on
                // every page; do not expose private paths/hosts in diagnostics.
                AppLogRecorder.recordEvent("RepositoryIndex", "SYNC_DEFERRED", GatewayIndexSyncObservation.deferred(failure = failure), "W")
            }
        }
    }
}

/** Closed classifications only: exception messages may contain private source paths or hosts. */
internal object GatewayIndexSyncObservation {
    private val admissionRepairs = mapOf(
        "PERMISSION_REVOKED" to setOf("RESTORE_SELECTED_GITHUB_CONNECTION"),
        "PERMISSION_DENIED" to setOf("REVIEW_SELECTED_GITHUB_TOOL_PERMISSIONS"),
        "GRAPH_CONFIGURATION_REQUIRED" to setOf("RESTORE_SELECTED_AI_PROFILE", "ENABLE_ENGINEERING_MEMORY", "SELECT_CONFIGURED_ENGINEERING_SURFACE"),
        "GRAPH_SELECTION_CHANGED" to setOf("RECHECK_SELECTED_GATEWAY")
    )

    fun deferred(completed: Boolean? = null, failure: Exception? = null): Map<String, Any?> {
        var observed = failure
        var reconcile = false
        repeat(4) {
            val operation = observed as? GatewayGraphOperationException
            if (operation != null) {
                reconcile = reconcile || operation.indexOutcomeUnknown
                observed = operation.cause as? Exception
            }
        }
        val reason: String
        val repair: String
        when (val cause = observed) {
            is GatewayGraphAdmissionException -> {
                val repairs = admissionRepairs[cause.code]
                reason = cause.code.takeIf { repairs != null } ?: "GRAPH_ADMISSION_REJECTED"
                repair = cause.repairAction?.takeIf { it in repairs.orEmpty() }
                    ?: if (cause.code == "PERMISSION_DENIED") "REVIEW_SELECTED_GITHUB_TOOL_PERMISSIONS" else "REVIEW_SELECTED_REPOSITORY_MAP_CONFIGURATION"
            }
            is GatewayGraphUnavailableException -> {
                val prerequisite = cause.prerequisites.firstOrNull() ?: GatewayGraphPrerequisite.READINESS_UNKNOWN
                reason = prerequisite.name
                repair = prerequisite.nextAction
            }
            is GatewayGraphHttpFailure -> {
                reason = "GRAPH_HTTP_REJECTED"
                repair = "CHECK_SAVED_INDEX"
            }
            null -> {
                reason = if (completed == null) "SYNC_TIMEOUT" else "SYNC_NOT_COMPLETED"
                repair = "INSPECT_SELECTED_REPOSITORY_INDEX"
            }
            else -> {
                reason = "SYNC_FAILED"
                repair = "INSPECT_SELECTED_REPOSITORY_INDEX"
            }
        }
        return buildMap {
            put("reason", reason)
            put("repair_action", if (reconcile) "CHECK_SAVED_INDEX" else repair)
            put("previous_snapshot", "unchanged")
            put("sync_complete", false)
            (observed as? GatewayGraphHttpFailure)?.httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
        }
    }
}
