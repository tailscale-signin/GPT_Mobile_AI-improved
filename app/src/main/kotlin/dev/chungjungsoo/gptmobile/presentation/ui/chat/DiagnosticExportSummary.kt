package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sharing a structurally valid ZIP is not proof that it contains diagnostic evidence. */
internal fun diagnosticExportSummary(manifest: JsonObject): String {
    val stages = (manifest["stages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    val logs = stages.filter { it.text("id") in setOf("critical_logs", "recent_logs") }.sumOf { it.text("evidenceRecords").toLongOrNull() ?: 0 }
    val unfinished = stages.count { it.text("status") in setOf("FAILED", "PARTIAL") }
    val resumable = manifest["resumable"] != JsonPrimitive(false)
    val retry = if (resumable) "Choose Low-memory diagnostics or Resume diagnostic capture to retry." else "Choose Low-memory diagnostics to start a new capture. Temporary captures cannot be resumed."
    return when {
        logs == 0L -> "No application log records were captured. The archive reports the available sections and missing evidence. $retry"
        unfinished > 0 ->
            "Partial diagnostics: $logs retained log records; " +
                (if (unfinished == 1) "1 section is incomplete. " else "$unfinished sections are incomplete. ") +
                (if (resumable) "Choose Resume diagnostic capture to retry unfinished sections." else "Choose Low-memory diagnostics to start a new capture. Temporary captures cannot be resumed.")
        manifest["reducedMemory"] == JsonPrimitive(true) -> "Low-memory diagnostics prepared with $logs retained log records. The manifest lists the captured sections and retention limits."
        else -> "Diagnostics prepared with $logs retained log records. The manifest lists the captured sections and retention limits."
    }
}
