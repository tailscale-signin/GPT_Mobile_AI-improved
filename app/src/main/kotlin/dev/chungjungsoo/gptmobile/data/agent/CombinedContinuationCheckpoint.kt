package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Persist writing progress independently of the repository's provider/execution coroutine. */
internal class CombinedContinuationCheckpoint(
    private val workspace: WorkspaceRepository?,
    private val source: MessageV2,
    profileUid: String,
    private val runId: String
) {
    private val coverage = CombinedCoverage(source.content)
    private val id = "synthesis:${source.chatId}:${source.id}:$profileUid:${WorkspaceRepository.digest(source.content)}"

    suspend fun restore(): String {
        val saved = workspace?.synthesisCheckpoint(id) ?: return ""
        val reason = (saved["terminalReason"] as? JsonPrimitive)?.content
        return if (reason in setOf("WRITING", "SEGMENT_COMMITTED", "PARTIAL", "CANCELLED")) (saved["draft"] as? JsonPrimitive)?.content.orEmpty() else ""
    }

    fun continuationContext(draft: String): String = coverage.continuationContext(draft)

    suspend fun save(draft: String, index: Int, cap: Int?, reason: String) {
        workspace?.saveSynthesisCheckpoint(
            id,
            source.chatId,
            runId,
            buildJsonObject {
                put("draft", draft)
                put("committedSections", JsonArray(draft.lineSequence().filter { it.startsWith("#") }.take(64).map { JsonPrimitive(it.take(180)) }.toList()))
                put("pendingOutline", coverage.pendingOutline(draft))
                put("sourceCoverage", coverage.manifest(draft))
                put("outputCap", cap ?: 0)
                put("continuationIndex", index)
                put("terminalReason", reason)
                put("parentRunId", runId)
            }
        )
    }
}
