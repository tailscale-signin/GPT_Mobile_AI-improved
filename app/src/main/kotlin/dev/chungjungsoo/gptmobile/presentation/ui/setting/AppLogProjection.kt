package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.GroupedAppLog
import dev.chungjungsoo.gptmobile.data.diagnostics.classifyDiagnostic

/** Computed on Default; Compose only displays the retained projection. */
internal data class AppLogRow(
    val key: String,
    val group: GroupedAppLog,
    val preview: String,
    val severity: String,
    val hasDetails: Boolean
)

internal data class AppLogProjection(
    val totalEvents: Int = 0,
    val warningEvents: Int = 0,
    val errorEvents: Int = 0,
    val tags: List<String> = listOf("All tags"),
    val rows: List<AppLogRow> = emptyList(),
    val visibleEvents: Int = 0,
    val visibleIssues: Int = 0
)

internal fun projectAppLogs(entries: List<AppLogEntry>, groups: List<GroupedAppLog>, errorsOnly: Boolean, selectedTag: String, query: String): AppLogProjection {
    val visible = groups.filter { group ->
        (!errorsOnly || group.incidentIds.isNotEmpty()) &&
            group.rawDetails.any { entry ->
                (selectedTag == "All tags" || entry.tag == selectedTag) &&
                    (entry.tag.contains(query, ignoreCase = true) || entry.message.contains(query, ignoreCase = true))
            }
    }.takeLast(100)
    return AppLogProjection(
        totalEvents = entries.size,
        warningEvents = entries.count { it.level == "W" || it.level == "E" || it.level == "F" },
        errorEvents = entries.count { it.level == "E" || it.level == "F" },
        tags = listOf("All tags") + entries.map { it.tag }.distinct().sorted(),
        rows = visible.asReversed().map { group ->
            val line = group.entry.line()
            AppLogRow(
                // The representative may become an error later; the group's
                // original event remains its identity as new records arrive.
                key = group.rawDetails.firstOrNull()?.eventId ?: group.entry.eventId,
                group = group,
                preview = line.take(2000) + if (line.length > 2000) "\n… Open raw details for the full event." else "",
                severity = classifyDiagnostic(group.entry.level, group.entry.tag, group.entry.message).severity,
                hasDetails = group.repetitions > 1 || line.length > 2000
            )
        },
        visibleEvents = visible.sumOf { it.repetitions },
        visibleIssues = visible.flatMap { it.incidentIds }.distinct().size
    )
}
