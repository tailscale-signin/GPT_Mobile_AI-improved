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
    val pendingSections = stages.filter { it.text("status") in setOf("FAILED", "PARTIAL") }.map { it.text("id") }.filter(String::isNotBlank)
    val applicationRecordingDisabled = stages.filter { it.text("id") in setOf("critical_logs", "recent_logs") }.any {
        (it["omissions"] as? JsonObject)?.containsKey("TRACKING_DISABLED_NO_RETAINED_LOGS") == true ||
            it.text("omissionReason") == "TRACKING_DISABLED_NO_RETAINED_LOGS"
    }
    val resumable = manifest["resumable"] != JsonPrimitive(false)
    val continuation = resumable && manifest["continuationAvailable"] == JsonPrimitive(true)
    val resume = if (continuation) "Choose Resume diagnostic capture to export the next archive part with new history records." else "Choose Resume diagnostic capture to retry unfinished sections."
    val newCapture = when {
        applicationRecordingDisabled -> "Enable application log recording, reproduce the issue, then start a new diagnostic capture. Network diagnostics uses a separate setting."
        manifest.text("recoveryAction") == "NEW_CAPTURE_REQUIRED" -> "Start a new diagnostic capture after reproducing the issue. Missing historical evidence cannot be recovered by resuming this archive."
        else -> "Start a new diagnostic capture. This capture cannot be resumed. Low-memory diagnostics is available when memory limits prevent collection."
    }
    val retry = when {
        applicationRecordingDisabled && resumable -> "$newCapture $resume"
        applicationRecordingDisabled || !resumable -> newCapture
        else -> "$resume Low-memory diagnostics starts a new capture with smaller limits."
    }
    val summary = when {
        logs == 0L -> "No application log records were captured. The archive reports the available sections and missing evidence. $retry"
        unfinished > 0 ->
            "Partial diagnostics: $logs retained log records; " +
                (if (unfinished == 1) "1 section is incomplete. " else "$unfinished sections are incomplete. ") +
                retry +
                if (pendingSections.isNotEmpty()) " Pending sections: ${pendingSections.joinToString(", ")}." else ""
        manifest["reducedMemory"] == JsonPrimitive(true) -> "Low-memory diagnostics prepared with $logs retained log records. The manifest lists the captured sections and retention limits."
        else -> "Diagnostics prepared with $logs retained log records. The manifest lists the captured sections and retention limits."
    }
    val completed = stages.count { it.text("status") in setOf("COMPLETED", "EMPTY") }
    val coverage = "Section coverage: $completed of ${stages.size} requested sections are complete or referenced. This does not measure the percentage of history captured."
    val parts = when {
        manifest["externalPartsRequired"] == JsonPrimitive(true) -> {
            val previous = (manifest["previousArchive"] as? JsonObject)?.text("fileName").orEmpty()
            "This is archive part ${manifest.text("archivePart")}. Keep it with ${previous.ifBlank { "the preceding archive" }} and earlier linked parts; their history records are not repeated here."
        }
        continuation -> "Keep this archive; later parts identify it by filename and digest. All parts are needed to read the captured history."
        else -> ""
    }
    return listOf(summary, coverage, parts).filter { it.isNotBlank() }.joinToString(" ")
}
