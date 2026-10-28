package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only complete inspection reports are reusable; identity includes the full frozen input and authority. */
internal class CombinedInspectionCheckpoint(
    private val workspace: WorkspaceRepository?,
    plan: CombinedCorpusMultipassPlan,
    system: String,
    limits: TokenBudgetSettings,
    authority: String,
    private val chatId: Int,
    private val runId: String
) {
    private val identity = WorkspaceRepository.digest(
        listOf("combined-inspection-v1", authority, system, limits.toString(), plan.inspectionOutputTokens.toString(), plan.frozenTurns.toString()).joinToString("\u0000")
    )

    private fun key(pass: CombinedCorpusInspectionPass) = "inspection:$chatId:$identity:${pass.ordinal}"

    suspend fun restore(pass: CombinedCorpusInspectionPass): String? {
        val saved = workspace?.protectedSynthesisCheckpoint(key(pass)) ?: return null
        fun field(name: String) = (saved[name] as? JsonPrimitive)?.content
        val report = field("report") ?: return null
        return report.takeIf {
            it.isNotBlank() &&
                it.length <= 65_536 &&
                field("identity") == identity &&
                field("sourceId") == pass.source.sourceId &&
                field("sourceHash") == WorkspaceRepository.digest(pass.source.text) &&
                field("reportHash") == WorkspaceRepository.digest(it)
        }
    }

    suspend fun save(pass: CombinedCorpusInspectionPass, report: String) {
        require(report.isNotBlank() && report.length <= 65_536)
        workspace?.saveProtectedSynthesisCheckpoint(
            key(pass),
            chatId,
            runId,
            buildJsonObject {
                put("identity", identity)
                put("sourceId", pass.source.sourceId)
                put("sourceHash", WorkspaceRepository.digest(pass.source.text))
                put("reportHash", WorkspaceRepository.digest(report))
                put("report", report)
            }
        )
    }
    suspend fun restoreConsolidation(prompt: String): String? {
        val hash = WorkspaceRepository.digest(prompt)
        val saved = workspace?.protectedSynthesisCheckpoint("consolidation:$chatId:$identity:$hash") ?: return null
        fun field(name: String) = (saved[name] as? JsonPrimitive)?.content
        val report = field("report") ?: return null
        return report.takeIf { it.isNotBlank() && it.length <= 65_536 && field("identity") == identity && field("promptHash") == hash && field("reportHash") == WorkspaceRepository.digest(it) }
    }

    suspend fun saveConsolidation(prompt: String, report: String) {
        require(report.isNotBlank() && report.length <= 65_536)
        val hash = WorkspaceRepository.digest(prompt)
        workspace?.saveProtectedSynthesisCheckpoint(
            "consolidation:$chatId:$identity:$hash",
            chatId,
            runId,
            buildJsonObject {
                put("identity", identity)
                put("promptHash", hash)
                put("reportHash", WorkspaceRepository.digest(report))
                put("report", report)
            }
        )
    }
}
